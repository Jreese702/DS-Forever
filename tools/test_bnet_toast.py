"""chat40 (the user, 2026-11-16: "the Bnet friends coming online pop ups should appear over my chat similar to the world refreesh prompt.
both of those should be getting hidden from the top screen. the world refresh prompt should have blue text and the ! notice part should
be a button to trigger the refresh."): the REAL Chat.lua (with Safety.lua and TapPad.lua) under tools/wowstub.py.
- the game's toasts over the top-screen chat (ShardTransferImminentFrame, its minimize button, BNToastFrame) are VEILED at their show:
  alpha 0 on the frame and on every piece, the glow's vertex alpha 0, the mouse off; never Hide / Show / SetParent / a CVar write;
- the BN toast only for its online / offline toasts (toastType 1 / 2); an invite toast after one gets its looks and mouse back;
- BN_FRIEND_ACCOUNT_ONLINE / _OFFLINE -> a `notice` {kind "bnet"} with the character name, else the BattleTag's name, never the account
  name or a |K string; a companion-app login and a toast the game's own settings turn off send nothing;
- chcmd column 1 (the notice's "!") calls C_PlayerInteractionManager.ShardTransferConfirm only while a world refresh is on, on the
  PRESS (TapPad pad6b onPress: a release-only cell can be lost after a controller press).
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import ADDONS, boot, docs      # noqa: E402

CHAT = open(os.path.join(ADDONS, "WowCompanion", "Chat.lua"), encoding="utf-8").read()

PRE = r'''
WowCompanion.capture.take = function() return false, "busy" end
WowCompanion.capture.release = function() return true end
C_ChatInfo = { SendChatMessage = function() end }
function IsInGuild() return false end
function IsInGroup() return false end
function IsInRaid() return false end
C_AddOns = { IsAddOnLoaded = function() return true end, GetAddOnInfo = function(a) return a, a, "", true, nil end }

-- the game's toasts: pieces with an alpha, a glow with a vertex colour, the fade-in group; every Hide / Show / SetParent counted
TOASTCALLS = {}
local function piece(name)
    local p = { _a = 1, _name = name }
    function p:SetAlpha(a) self._a = a end
    function p:GetAlpha() return self._a end
    function p:Hide() TOASTCALLS[#TOASTCALLS + 1] = "Hide " .. self._name end
    function p:Show() TOASTCALLS[#TOASTCALLS + 1] = "Show " .. self._name end
    return p
end
local function toast(name)
    local fr = piece(name)
    fr._mouse = true
    fr._shown = false
    fr._hooks = {}
    fr._kids = { piece(name .. ".CloseButton") }
    fr._regions = { piece(name .. ".Text"), piece(name .. ".Backdrop") }
    local g = piece(name .. ".glow")
    g._vc = { 1, 1, 1, 1 }
    function g:SetVertexColor(r, gg, b, a) self._vc = { r, gg, b, a or 1 } end
    function g:GetVertexColor() return unpack(self._vc) end
    fr.glow = g
    fr._regions[#fr._regions + 1] = g
    local anim = { _hooks = {} }
    function anim:HookScript(k, fn) self._hooks[k] = self._hooks[k] or {}; table.insert(self._hooks[k], fn) end
    fr.animIn = anim
    function fr:IsMouseEnabled() return self._mouse end
    function fr:EnableMouse(on) self._mouse = on end
    function fr:IsProtected() return false end
    function fr:IsShown() return self._shown end
    function fr:GetChildren() return unpack(self._kids) end
    function fr:GetRegions() return unpack(self._regions) end
    function fr:HookScript(k, fn) self._hooks[k] = self._hooks[k] or {}; table.insert(self._hooks[k], fn) end
    function fr:SetParent() TOASTCALLS[#TOASTCALLS + 1] = "SetParent " .. name end
    -- the game's own show: shown, then its OnShow hooks, then the fade-in (it drives the frame's alpha 0 -> 1)
    function fr:GameShow()
        self._shown = true
        for _, fn in ipairs(self._hooks.OnShow or {}) do fn(self) end
        for _, fn in ipairs(anim._hooks.OnPlay or {}) do fn(anim) end
    end
    rawset(_G, name, fr)
    return fr
end
toast("ShardTransferImminentFrame"); toast("ShardTransferImminentMinimizeButton"); toast("BNToastFrame")

CVARS = { showToastWindow = true, showToastOnline = true, showToastOffline = true }
C_CVar = { GetCVarBool = function(k) return CVARS[k] end, SetCVar = function() SPY[#SPY + 1] = "C_CVar.SetCVar" end,
           GetCVarInfo = function() return "classic", "classic" end }
ACCOUNTS = {
    [11] = { accountName = "|Kq11|k", battleTag = "Palfriend#1234", gameAccountInfo = { characterName = "Thrall" } },
    [12] = { accountName = "|Kq12|k", battleTag = "Quietone#5678", gameAccountInfo = {} },
    [13] = { accountName = "|Kq13|k", battleTag = "|Kb13|k", gameAccountInfo = { characterName = "|Kc13|k" } },
}
C_BattleNet = { GetAccountInfoByID = function(id) return ACCOUNTS[id] end }
CONFIRMED = 0
C_PlayerInteractionManager = { ShardTransferConfirm = function() CONFIRMED = CONFIRMED + 1 end }
EVICT = nil
function GetEvictionTimeRemaining() return EVICT end
function StaticPopup_Visible() return nil end
SHARD_TRANSFER_COUNTDOWN_STATIC_POPUP_MESSAGE = "The world around you will refresh in %d %s"
MINUTES, SECONDS = "Minutes", "Seconds"
SHARD_TRANSFER_ANYTIME = "Refreshing your world at any time."
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
ok(L.eval('#ERRS') == 0, "no Lua errors at the login: %s" % L.eval('table.concat(ERRS, " | ")'))
ok('local CHATV = "chat40"' in CHAT, "Chat.lua is chat40")


def alpha(expr):
    return float(L.eval(expr))


# ---- 1. the shard toast and its minimize button: veiled at the game's show
L.execute('ShardTransferImminentFrame:GameShow(); ShardTransferImminentMinimizeButton:GameShow()')
for name in ("ShardTransferImminentFrame", "ShardTransferImminentMinimizeButton"):
    ok(alpha('%s:GetAlpha()' % name) == 0, "%s: the frame's alpha is 0" % name)
    ok(alpha('%s._kids[1]:GetAlpha()' % name) == 0 and alpha('%s._regions[1]:GetAlpha()' % name) == 0
       and alpha('%s._regions[2]:GetAlpha()' % name) == 0, "%s: every piece's alpha is 0 (the fade has nothing to show)" % name)
    ok(alpha('select(4, %s.glow:GetVertexColor())' % name) == 0, "%s: the glow's vertex alpha is 0 (its own fade)" % name)
    ok(L.eval('%s._mouse' % name) is False, "%s: the invisible toast takes no click" % name)

# ---- 2. the BN toast: online / offline veiled; an invite toast is the game's again
L.execute('BNToastFrame.toastType = 1; BNToastFrame:GameShow()')
ok(alpha('BNToastFrame:GetAlpha()') == 0 and alpha('BNToastFrame._regions[1]:GetAlpha()') == 0, "the BN online toast is veiled")
ok(L.eval('BNToastFrame._mouse') is False, "the BN online toast takes no click (its click would whisper)")
L.execute('BNToastFrame._shown = false; BNToastFrame.toastType = 3; BNToastFrame:GameShow()')
ok(alpha('BNToastFrame:GetAlpha()') == 1 and alpha('BNToastFrame._regions[1]:GetAlpha()') == 1
   and alpha('select(4, BNToastFrame.glow:GetVertexColor())') == 1, "an invite toast (not mirrored) shows as the game made it")
ok(L.eval('BNToastFrame._mouse') is True, "the invite toast takes its click again")
L.execute('BNToastFrame._shown = false; BNToastFrame.toastType = 2; BNToastFrame:GameShow()')
ok(alpha('BNToastFrame:GetAlpha()') == 0, "the BN offline toast is veiled")
ok(L.eval('#TOASTCALLS') == 0, "never Hide / Show / SetParent on a toast: %s" % L.eval('table.concat(TOASTCALLS, ", ")'))

# ---- 3. the BN notices
n0 = len(docs(L))
L.execute('fire("BN_FRIEND_ACCOUNT_ONLINE", 11, false)')
L.execute('fire("BN_FRIEND_ACCOUNT_OFFLINE", 12, false)')
L.execute('fire("BN_FRIEND_ACCOUNT_ONLINE", 13, false)')
L.execute('fire("BN_FRIEND_ACCOUNT_ONLINE", 11, true)')                 # the companion app: never toasted
L.execute('CVARS.showToastOffline = false; fire("BN_FRIEND_ACCOUNT_OFFLINE", 11, false); CVARS.showToastOffline = true')
L.execute('CVARS.showToastWindow = false; fire("BN_FRIEND_ACCOUNT_ONLINE", 11, false); CVARS.showToastWindow = true')
bn = [d for d in docs(L)[n0:] if d.get("type") == "notice" and d.get("kind") == "bnet"]
ok(len(bn) == 3, "three BN notices (companion app and settings-off skipped): %r" % bn)
ok(bn[0].get("who") == "Thrall" and bn[0].get("on") is True and not bn[0].get("bt"), "online: the character they play: %r" % bn[0])
ok(bn[1].get("who") == "Quietone" and bn[1].get("on") is False and bn[1].get("bt") is True, "offline: the BattleTag's name: %r" % bn[1])
ok(bn[2].get("who") is None, "only protected names: no name at all (the app says A friend): %r" % bn[2])
ok(all(d.get("v") == "chat40" for d in bn) and len({d.get("id") for d in bn}) == 3, "each its own id, chat40")
raw = json.dumps(bn)
ok("|K" not in raw and "Palfriend#" not in raw, "never the account name, a |K string or a whole BattleTag: %s" % raw)
ok(not any(d.get("type") == "chat" and "Thrall" in json.dumps(d) for d in docs(L)[n0:]), "a BN login is not a chat line")

# ---- 4. the "!" press: chcmd column 1
cell = 'WowCompanion.pad.rows.chcmd.cells[1]'
ok(L.eval('WowCompanion.pad.rows.chcmd ~= nil and WowCompanion.pad.rows.chcmd.n') == 2, "chcmd has two cells (no new row)")
ok(L.eval('%s ~= nil and %s:GetAttribute("type") == nil' % (cell, cell)), "column 1 has no secure action")
ok(L.eval('WowCompanion.pad.rows.chcmd.cells[0]:GetAttribute("type")') == "macro", "column 0 is still the /command macro")


def press():
    L.execute('NOW = NOW + 1; %s:Click("LeftButton", true); %s:Click("LeftButton", false)' % (cell, cell))
    return [d for d in docs(L) if d.get("type") == "notice" and d.get("op") == "go"][-1]


g = press()
ok(L.eval('CONFIRMED') == 0 and g.get("ok") is False and g.get("why") == "no world refresh", "no refresh on: refused: %r" % g)
L.execute('EVICT = 240; tick(1.0)')
ok(any(d.get("kind") == "shard" and d.get("left") == 240 for d in docs(L, typ="notice")), "the world refresh notice is on")
L.execute('COMBAT = true')
g = press()
ok(L.eval('CONFIRMED') == 1 and g.get("ok") is True, "the press is the game's Refresh now, in a fight too (not protected): %r" % g)
L.execute('COMBAT = false; NOW = NOW + 1')
# the press alone runs it (TapPad pad6b onPress): a release lost after a controller press never loses the refresh
L.execute('%s:Click("LeftButton", true)' % cell)
ok(L.eval('CONFIRMED') == 2, "the refresh runs on the PRESS (no release needed)")
L.execute('%s:Click("LeftButton", false)' % cell)
ok(L.eval('CONFIRMED') == 2, "and the release runs nothing more")
ok(L.eval('WowCompanion.pad.rows.chcmd.onPress') is True, "chcmd is registered with onPress")
ok(L.eval('#VIOL') == 0, "no protected work: %s" % L.eval('table.concat(VIOL, ", ")'))
ok(L.eval('#SPY') == 0, "no trace (SetCVar / SetBinding / macro / bar): %s" % L.eval('table.concat(SPY, ", ")'))
ok(L.eval('#ERRS') == 0, "no Lua errors: %s" % L.eval('table.concat(ERRS, " | ")'))
print("test_bnet_toast: %d checks passed" % passed)
