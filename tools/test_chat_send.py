"""chat33 (#183, 1.0.25: "Not able to send messages in chat/guild/whisper/party/etc"; #184 "Cannot send messages", its duplicate): the
REAL Chat.lua (with Safety.lua and TapPad.lua) under tools/wowstub.py, through the real pad cells:
- with the chat holding the controller, typed letters fill the buffer, the check (15) answers ok with its length + hash, and Send (23)
  makes ONE C_ChatInfo.SendChatMessage of exactly that text on the type picked (SAY, then GUILD in a guild, then a whisper);
- with something else holding, every key / check / send is refused "the chat is not open" and nothing is sent;
- with NO shared capture at all (DS Forever: Interact never ran, #183's log: every room's hold "no controller capture"), the refusal
  says "Interact add-on not running" and nothing is sent;
- the login's "ours" probe names each of our add-ons the game did not load with the game's reason, the capture's version (0: none) and
  the first Lua errors from our own files, seen by a secure post-hook on the game's error frame (chat35, issue #29: the game's error
  handler is never wrapped or replaced - a wrapper ran the error frame, and the gamepad UI focus it takes, as our code);
- nothing here is a trace: no SetBinding / SetCVar / macro / bar write (the stub's spies stay empty).
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import ADDONS, boot, docs, nsent      # noqa: E402

CHAT = open(os.path.join(ADDONS, "WowCompanion", "Chat.lua"), encoding="utf-8").read()

PRE = r'''
-- Interact's capture as the game has it (take / release; the stub's holder is HOLDER)
WowCompanion.capture.take = function() return false, "busy" end
WowCompanion.capture.release = function() return true end
SENTCHAT = {}
C_ChatInfo = { SendChatMessage = function(text, kind, lang, target)
    SENTCHAT[#SENTCHAT + 1] = tostring(kind) .. "|" .. tostring(target) .. "|" .. tostring(text) end }
IN_GUILD = false
function IsInGuild() return IN_GUILD end
function IsInGroup() return false end
function IsInRaid() return false end
-- the game's add-on list: Interact not loaded (its dependency's reason), every other one loaded
NOT_LOADED = { WowCompanionInteract = "DEP_MISSING" }
C_AddOns = {
    IsAddOnLoaded = function(a) return NOT_LOADED[a] == nil end,
    GetAddOnInfo = function(a) return a, a, "", false, NOT_LOADED[a] end,
}
-- the game's error handler and its error frame (the handler hands every message to the frame's OnError)
GAMEERR = {}
ScriptErrorsFrame = { OnError = function(self, msg) GAMEERR[#GAMEERR + 1] = msg end }
GAME_HANDLER = function(msg) return ScriptErrorsFrame:OnError(msg) end
local handler = GAME_HANDLER
function geterrorhandler() return handler end
function seterrorhandler(fn) SETEH = (rawget(_G, "SETEH") or 0) + 1; handler = fn end
function raise(msg) return handler(msg) end
'''


def world(capture=True, holder="pad:chat", pre_extra=""):
    pre = PRE + "\n" + pre_extra + "\n"
    if not capture:
        pre += "\nWowCompanion.capture = nil\n"
    L = boot(["safety", "tappad"], pre=pre)
    load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    load(CHAT, "@Chat.lua")("WowCompanion")
    L.execute('HOLDER = %s' % ('nil' if holder is None else '"%s"' % holder))
    L.execute('fire("PLAYER_LOGIN"); runTimers(NOW + 3)')
    return L


def tap(L, row, col):
    assert L.eval('type(WowCompanion.pad.rows["%s"].cells[%d])' % (row, col)) == "table", "no cell %s %d" % (row, col)
    # both edges of the tap on the real cell, then past the cell's 0.25 s repeat guard
    L.execute('local c = WowCompanion.pad.rows["%s"].cells[%d]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false); '
              'NOW = NOW + 0.3' % (row, col))


def cell(ch):
    c = ord(ch)
    if 32 <= c <= 47:
        return "chq", c - 8
    if 48 <= c <= 87:
        return "chr", c - 48
    return "chs", c - 88


def type_text(L, s):
    for ch in s:
        tap(L, *cell(ch))


def sentchat(L):
    return [L.eval('SENTCHAT[%d]' % i) for i in range(1, int(L.eval('#SENTCHAT')) + 1)]


def last(L, typ, start=0):
    d = docs(L, start, typ)
    assert d, "no %s answer" % typ
    return d[-1]


def h(s):
    v = 0
    for b in s.encode():
        v = (v * 31 + b) % 16777213
    return v


passed = 0


def ok(cond, what):
    global passed
    assert cond, what
    passed += 1


# ---------------------------------------------------------------- 1. the chat holds: typed, checked, sent, exactly once
L = world()
type_text(L, "hi there")
n0 = nsent(L)
tap(L, "chq", 15)
a = last(L, "chatt", n0)
ok(a["op"] == "body" and a["ok"] is True and a["len"] == 8 and a["h"] == h("hi there"), "the check reports the buffer: %r" % a)
tap(L, "chq", 23)
ok(sentchat(L) == ["SAY|nil|hi there"], "one SAY send of the typed text: %r" % sentchat(L))
ok(last(L, "chatt")["ok"] is True and last(L, "chatt")["len"] == 0, "the send answered ok, the buffer spent")

# GUILD: refused out of a guild (nothing sent), sent in one
tap(L, "chq", 19)
ok(last(L, "chatq")["ok"] is True and last(L, "chatq")["ty"] == "GUILD", "the type switched to GUILD")
type_text(L, "lfg")
tap(L, "chq", 23)
ok(last(L, "chatt")["ok"] is False and last(L, "chatt")["why"] == "not in a guild", "GUILD out of a guild: refused")
ok(len(sentchat(L)) == 1, "nothing sent out of a guild")
L.execute("IN_GUILD = true")
tap(L, "chq", 23)
ok(sentchat(L)[-1] == "GUILD|nil|lfg", "GUILD in a guild: sent %r" % sentchat(L))

# a whisper to a spelt target
tap(L, "chq", 22)
tap(L, "chq", 14)
type_text(L, "Thrall")
tap(L, "chq", 15)
type_text(L, "yo")
tap(L, "chq", 23)
ok(sentchat(L)[-1] == "WHISPER|Thrall|yo", "the whisper went to the spelt name: %r" % sentchat(L))

# a preset on the current type
tap(L, "chq", 16)
tap(L, "chq", 0)
ok(sentchat(L)[-1] == "SAY|nil|Hello!", "a preset sends its phrase")
ok(L.eval('#SPY') == 0, "no trace: %r" % L.eval('table.concat(SPY, ",")'))

# ---------------------------------------------------------------- 2. another room holds: refused, nothing sent
L = world(holder="pad:quests")
n0 = nsent(L)
type_text(L, "hi")
tap(L, "chq", 15)
a = last(L, "chatt", n0)
ok(a["ok"] is False and a["why"] == "the chat is not open" and a["len"] == 0, "not held: the check refused: %r" % a)
tap(L, "chq", 23)
tap(L, "chq", 0)
ok(sentchat(L) == [], "not held: nothing sent")
ok(last(L, "chatq")["why"] == "the chat is not open", "a preset refused the same way")

# ---------------------------------------------------------------- 3. #183: NO capture (Interact never ran)
L = world(capture=False)
n0 = nsent(L)
type_text(L, "hi")
tap(L, "chq", 15)
a = last(L, "chatt", n0)
ok(a["ok"] is False and a["why"] == "Interact add-on not running", "no capture: the refusal says why: %r" % a)
tap(L, "chq", 17)
ok(last(L, "chatq")["ok"] is False and last(L, "chatq")["why"] == "Interact add-on not running", "a type pick says why too")
tap(L, "chq", 23)
ok(sentchat(L) == [], "no capture: nothing sent")

# the login's probe
probe = [d for d in docs(L) if d.get("type") == "cap" and d.get("probe") == "ours"]
ok(len(probe) == 1, "one ours probe a login: %r" % probe)
p = probe[0]
ok(p["capv"] == 0 and p["missing"] == 1 and p["loaded"] == 9, "capture none, one add-on missing: %r" % p)
ok(p["off"] == ["WowCompanionInteract:DEP_MISSING"], "the game's reason named: %r" % p["off"])

# with the capture there, the probe says its version and nothing missing
L2 = world()
L2.execute('NOT_LOADED = {}; fire("PLAYER_LOGIN"); runTimers(NOW + 3)')
p2 = [d for d in docs(L2) if d.get("probe") == "ours"][-1]
ok(p2["capv"] == 13 and p2["missing"] == 0 and p2["errN"] == 0, "all loaded: %r" % p2)
ok(p2["istage"] == "none" and p2["eh"] == "hook", "no Interact file here: never ran, errors caught by the frame hook: %r" % p2)
# chat35 (issue #29): the game's own error handler stays the game's (never wrapped, never replaced)
ok(L2.eval("geterrorhandler() == GAME_HANDLER") is True and L2.eval("rawget(_G, \"SETEH\")") is None, "the game's error handler untouched")

# chat34 (#183 review): how far Interact.lua ran, and whether the error handler is still ours
L2.execute('WowCompanion.interactStage = "guard"; fire("PLAYER_LOGIN"); runTimers(NOW + 3)')
p2 = [d for d in docs(L2) if d.get("probe") == "ours"][-1]
ok(p2["istage"] == "guard", "a file that stopped halfway is named: %r" % p2)
# a client with no error frame method: nothing hooked, the probe says errors are not caught
L4 = world(pre_extra="ScriptErrorsFrame = nil")
p4 = [d for d in docs(L4) if d.get("probe") == "ours"][-1]
ok(p4["eh"] == "none" and L4.eval("geterrorhandler() == GAME_HANDLER") is True, "no error frame: eh none, handler untouched: %r" % p4)
# chat36 (issue #29 review): the error frame's own add-on loads after ours - the hook is tried again at every ADDON_LOADED
L5 = world(pre_extra="SEF = ScriptErrorsFrame; ScriptErrorsFrame = nil")
L5.execute('WowCompanion.interactStage = nil; ScriptErrorsFrame = SEF; fire("ADDON_LOADED", "Blizzard_ScriptErrors")')
L5.execute('raise("Interface/AddOns/WowCompanionInteract/Interact.lua:9: early"); raise("Interface/AddOns/Other/o.lua:1: x")')
L5.execute('fire("PLAYER_LOGIN"); runTimers(NOW + 3)')
p5 = [d for d in docs(L5) if d.get("probe") == "ours"][-1]
ok(p5["eh"] == "hook" and p5["ehN"] == 2 and p5["ehM"] == "OnError" and p5["errN"] == 1,
   "hooked at the frame's ADDON_LOADED, before Interact ran: eh hook, both seen, ours kept: %r" % p5)
ok(L5.eval("geterrorhandler() == GAME_HANDLER") is True and int(L5.eval("#GAMEERR")) == 2, "the game's handler still the game's, ran both")
# hooked only after Interact.lua had started: eh late (its load errors may be missed)
L6 = world(pre_extra="SEF = ScriptErrorsFrame; ScriptErrorsFrame = nil")
L6.execute('WowCompanion.interactStage = "end"; ScriptErrorsFrame = SEF; fire("ADDON_LOADED", "Blizzard_ScriptErrors")')
L6.execute('fire("PLAYER_LOGIN"); runTimers(NOW + 3)')
p6 = [d for d in docs(L6) if d.get("probe") == "ours"][-1]
ok(p6["eh"] == "late" and p6["ehN"] == 0, "hooked after Interact started: eh late: %r" % p6)
# hooked once only (a second ADDON_LOADED adds no second hook)
L5.execute('fire("ADDON_LOADED", "Other"); raise("Interface/AddOns/Other/o.lua:2: y"); fire("PLAYER_LOGIN"); runTimers(NOW + 3)')
p5 = [d for d in docs(L5) if d.get("probe") == "ours"][-1]
ok(p5["ehN"] == 3, "one hook: each message counted once: %r" % p5)

# the REAL Interact.lua marks its stages: with no WowCompanion.S it stops at its guard ("top"); its last line is the end marker
INTERACT = open(os.path.join(ADDONS, "WowCompanionInteract", "Interact.lua"), encoding="utf-8").read()
ok(INTERACT.rstrip().endswith("WC.interactIV = IV"), "the end marker is the file's last line")
L3 = world()
L3.execute('WowCompanion.S = nil; WowCompanion.interactStage = nil')
load3 = L3.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
load3(INTERACT, "@Interact.lua")("WowCompanionInteract")
ok(L3.eval("WowCompanion.interactStage") == "top", "the guard stops it at top")

# ---------------------------------------------------------------- 4. the first Lua errors from OUR files (the game's handler still runs)
L.execute('raise("Interface/AddOns/WowCompanionInteract/Interact.lua:123: attempt to index a nil value")')
L.execute('raise("Interface/AddOns/SomeoneElse/x.lua:1: boom")')
ok(int(L.eval('#GAMEERR')) == 2, "the game's own handler got both errors")
L.execute('fire("PLAYER_LOGIN"); runTimers(NOW + 3)')
p = [d for d in docs(L) if d.get("probe") == "ours"][-1]
ok(p["errN"] == 1 and "WowCompanionInteract/Interact.lua:123" in p["errs"][0], "only our file's error kept: %r" % p.get("errs"))
for k in range(5):
    L.execute('raise("Interface/AddOns/WowCompanion/Chat.lua:%d: x")' % k)
ok(int(L.eval('#WowCompanion.ourErrors')) == 3, "three errors at most")
ok(L.eval('#SPY') == 0, "no trace")

print("test_chat_send: %d checks passed" % passed)
