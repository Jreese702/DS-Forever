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
chat41 (the user's pick, 2026-11-16: "Yes, all of them"): EVERY toast bnet.lua's BNToastFrame shows is veiled (toastType 1 .. 7; an
unknown one stays the game's) and copied: friend requests (new + pending count), broadcasts (the text only when plain; protected = none),
community invites and community-finder invites, each only when the game's own settings (read only) would toast it, with the game's
toast time (toastDuration) as hold; never a |K string or a whole BattleTag; nothing accepted or declined.
chat42 (the user, 2026-11-16: "There is a little white flash animation that happens whenever someone comes online in battlenet. We need
to hit that on the top screen and make something similar on the bottom"): the flash is the toast's glow (SocialToastTemplate's
$parentGlowFrame, UI-Toast-Flair, alphaMode ADD), whose own animIn (0 -> 1 -> 0 in 0.7 s) AlertFrame_PlayIntroAnimation plays after
the frame's show and which drives the glow's alpha while it plays: the stub plays it as the game does (its OnPlay hooks, then the alpha
the animation sets, vertex alpha included), and what the glow adds (colour x alpha) must be 0 on every veiled toast; the glow's colour
comes back with the rest on a toast that is the game's.
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
    -- chat42: the glow's own flash (SocialToastGlowTemplate animIn): its OnPlay hooks, then the alpha the animation drives
    local ganim = { _hooks = {} }
    function ganim:HookScript(k, fn) self._hooks[k] = self._hooks[k] or {}; table.insert(self._hooks[k], fn) end
    g.animIn = ganim
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
        -- AlertFrame_PlayIntroAnimation: frame.glow:Show(); frame.glow.animIn:Play() -> mid-flash, the animation's alpha 1
        for _, fn in ipairs(ganim._hooks.OnPlay or {}) do fn(ganim) end
        g._a = 1; g._vc[4] = 1
    end
    -- what the glow adds to the screen mid-flash (alphaMode ADD: colour x alpha x the texture's own alpha)
    function fr:GlowLight()
        local r, gg, b, a = g:GetVertexColor()
        return math.max(r, gg, b) * a * g._a
    end
    rawset(_G, name, fr)
    return fr
end
toast("ShardTransferImminentFrame"); toast("ShardTransferImminentMinimizeButton"); toast("BNToastFrame")

CVARS = { showToastWindow = true, showToastOnline = true, showToastOffline = true }
CVARS.showToastBroadcast = true; CVARS.showToastFriendRequest = true; CVARS.showToastClubInvitation = true
CVARS.toastDuration = "5"
C_CVar = { GetCVarBool = function(k) return CVARS[k] end, SetCVar = function() SPY[#SPY + 1] = "C_CVar.SetCVar" end,
           GetCVar = function(k) return CVARS[k] end,
           GetCVarInfo = function() return "classic", "classic" end }
ACCOUNTS = {
    [11] = { accountName = "|Kq11|k", battleTag = "Palfriend#1234", gameAccountInfo = { characterName = "Thrall" } },
    [12] = { accountName = "|Kq12|k", battleTag = "Quietone#5678", gameAccountInfo = {} },
    [13] = { accountName = "|Kq13|k", battleTag = "|Kb13|k", gameAccountInfo = { characterName = "|Kc13|k" } },
    [14] = { accountName = "|Kq14|k", battleTag = "Palfriend#1234", gameAccountInfo = { characterName = "Thrall" }, customMessage = "LFG Deadmines" },
    [15] = { accountName = "|Kq15|k", battleTag = "Quietone#5678", gameAccountInfo = {}, customMessage = SECRETV },
    [16] = { accountName = "|Kq16|k", battleTag = "Quietone#5678", gameAccountInfo = {}, customMessage = "" },
}
-- C_ClubFinder: the guid "g1" approved, "g2" pending, "g3" approved but already joined
Enum = { PlayerClubRequestStatus = { Approved = 2, Pending = 0 } }
C_ClubFinder = {
    DoesPlayerBelongToClubFromClubGUID = function(g) return g == "g3" end,
    GetRecruitingClubInfoFromFinderGUID = function(g) return { clubFinderGUID = g, name = "Finders " .. g } end,
    GetPlayerClubApplicationStatus = function(g) return (g == "g2") and 0 or 2 end,
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
ok('local CHATV = "chat43"' in CHAT, "Chat.lua is chat43")


def alpha(expr):
    return float(L.eval(expr))


# ---- 1. the shard toast and its minimize button: veiled at the game's show
L.execute('ShardTransferImminentFrame:GameShow(); ShardTransferImminentMinimizeButton:GameShow()')
for name in ("ShardTransferImminentFrame", "ShardTransferImminentMinimizeButton"):
    ok(alpha('%s:GetAlpha()' % name) == 0, "%s: the frame's alpha is 0" % name)
    ok(alpha('%s._kids[1]:GetAlpha()' % name) == 0 and alpha('%s._regions[1]:GetAlpha()' % name) == 0
       and alpha('%s._regions[2]:GetAlpha()' % name) == 0, "%s: every piece's alpha is 0 (the fade has nothing to show)" % name)
    ok(alpha('(select(1, %s.glow:GetVertexColor()))' % name) == 0, "%s: the glow's colour is black (chat42: its own fade drives its alpha)" % name)
    ok(L.eval('%s._mouse' % name) is False, "%s: the invisible toast takes no click" % name)
    ok(alpha('%s:GlowLight()' % name) == 0, "chat42: %s: the glow's flash adds nothing mid-animation (the white flash)" % name)

# ---- 2. the BN toast: every toast bnet.lua has is veiled (chat41); a toast type it does not know is the game's again
L.execute('BNToastFrame.toastType = 1; BNToastFrame:GameShow()')
ok(alpha('BNToastFrame:GetAlpha()') == 0 and alpha('BNToastFrame._regions[1]:GetAlpha()') == 0, "the BN online toast is veiled")
ok(L.eval('BNToastFrame._mouse') is False, "the BN online toast takes no click (its click would whisper)")
ok(alpha('BNToastFrame:GlowLight()') == 0, "chat42: no white flash on the top screen when a friend comes online (the glow's own animIn)")
L.execute('BNToastFrame.toastType = 1; BNToastFrame:GameShow()')       # a second friend while the toast is still up: the glow replays
ok(alpha('BNToastFrame:GlowLight()') == 0, "chat42: nor when the next friend's toast replays the glow on the toast still shown")
for t in (3, 4, 5, 6, 7):
    L.execute('BNToastFrame._shown = false; BNToastFrame.toastType = %d; BNToastFrame:GameShow()' % t)
    ok(alpha('BNToastFrame:GetAlpha()') == 0 and alpha('BNToastFrame._regions[1]:GetAlpha()') == 0
       and alpha('(select(1, BNToastFrame.glow:GetVertexColor()))') == 0, "chat41: BN toast type %d is veiled too" % t)
    ok(alpha('BNToastFrame:GlowLight()') == 0, "chat42: BN toast type %d: no glow flash" % t)
    ok(L.eval('BNToastFrame._mouse') is False, "chat41: BN toast type %d takes no click" % t)
L.execute('BNToastFrame._shown = false; BNToastFrame.toastType = 8; BNToastFrame:GameShow()')
ok(alpha('BNToastFrame:GetAlpha()') == 1 and alpha('BNToastFrame._regions[1]:GetAlpha()') == 1
   and alpha('select(4, BNToastFrame.glow:GetVertexColor())') == 1, "a toast type bnet.lua does not have shows as the game made it")
ok(L.eval('BNToastFrame._mouse') is True, "that toast takes its click again")
ok(L.eval('(select(1, BNToastFrame.glow:GetVertexColor()))') == 1 and alpha('BNToastFrame:GlowLight()') == 1,
   "chat42: that toast's glow flashes as the game made it (its colour back)")
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
L.execute('fire("BN_FRIEND_ACCOUNT_ONLINE", 99, false)')                 # a friend the game cannot read yet: its toast shows nothing
bn = [d for d in docs(L)[n0:] if d.get("type") == "notice" and d.get("kind") == "bnet"]
ok(len(bn) == 3, "three BN notices (companion app, settings-off and unreadable friend skipped): %r" % bn)
ok([d.get("t") for d in bn] == ["on", "off", "on"], "chat41: t says the toast: %r" % bn)
ok(all(d.get("hold") == 5 for d in bn), "chat41: the game's toast time (toastDuration) as hold: %r" % bn)
ok(bn[0].get("who") == "Thrall" and bn[0].get("on") is True and not bn[0].get("bt"), "online: the character they play: %r" % bn[0])
ok(bn[1].get("who") == "Quietone" and bn[1].get("on") is False and bn[1].get("bt") is True, "offline: the BattleTag's name: %r" % bn[1])
ok(bn[2].get("who") is None, "only protected names: no name at all (the app says A friend): %r" % bn[2])
ok(all(d.get("v") == "chat43" for d in bn) and len({d.get("id") for d in bn}) == 3, "each its own id, chat43")
raw = json.dumps(bn)
ok("|K" not in raw and "Palfriend#" not in raw, "never the account name, a |K string or a whole BattleTag: %s" % raw)
ok(not any(d.get("type") == "chat" and "Thrall" in json.dumps(d) for d in docs(L)[n0:]), "a BN login is not a chat line")

# ---- 3b. chat41: every other Battle.net toast (the user's pick, 2026-11-16: "Yes, all of them")
n1 = len(docs(L))
L.execute('fire("BN_FRIEND_INVITE_ADDED", 7)')
L.execute('fire("BN_FRIEND_INVITE_LIST_INITIALIZED", 3)')
L.execute('fire("BN_CUSTOM_MESSAGE_CHANGED", 14)')                       # plain text
L.execute('fire("BN_CUSTOM_MESSAGE_CHANGED", 15)')                       # protected text: none sent
L.execute('fire("BN_CUSTOM_MESSAGE_CHANGED", 16)')                       # cleared ("" = the game shows no toast)
L.execute('fire("BN_CUSTOM_MESSAGE_CHANGED", nil)')                      # the player's own: no toast
L.execute('fire("BN_CUSTOM_MESSAGE_CHANGED", 99)')                       # unreadable friend: no toast
L.execute('fire("CLUB_INVITATION_ADDED_FOR_SELF", { club = { name = "Goldshire Book Club", clubType = 1 } })')
L.execute('fire("CLUB_INVITATION_ADDED_FOR_SELF", { club = { name = "|Kc9|k", clubType = 0 } })')
L.execute('fire("CLUB_FINDER_APPLICANT_INVITE_RECIEVED", { "g1", "g2", "g3" })')
# each setting off: nothing
L.execute('CVARS.showToastFriendRequest = false; fire("BN_FRIEND_INVITE_ADDED"); fire("BN_FRIEND_INVITE_LIST_INITIALIZED", 2); CVARS.showToastFriendRequest = true')
L.execute('CVARS.showToastBroadcast = false; fire("BN_CUSTOM_MESSAGE_CHANGED", 14); CVARS.showToastBroadcast = true')
L.execute('CVARS.showToastClubInvitation = false; fire("CLUB_INVITATION_ADDED_FOR_SELF", { club = { name = "X" } }); CVARS.showToastClubInvitation = true')
L.execute('CVARS.showToastWindow = false; fire("BN_FRIEND_INVITE_ADDED"); CVARS.showToastWindow = true')
more = [d for d in docs(L)[n1:] if d.get("type") == "notice" and d.get("kind") == "bnet"]
ok([d.get("t") for d in more] == ["invite", "pending", "bcast", "bcast", "club", "club", "club"],
   "invite, pending, two broadcasts, two community invites, one finder invite (the rest not toasted by the game): %r" % more)
ok(more[1].get("n") == 3, "the pending count: %r" % more[1])
ok(more[2].get("who") == "Thrall" and more[2].get("text") == "LFG Deadmines", "a plain broadcast: who + text: %r" % more[2])
ok(more[3].get("who") == "Quietone" and more[3].get("text") is None, "a protected broadcast: no text (the app says posted a broadcast): %r" % more[3])
ok(more[4].get("name") == "Goldshire Book Club", "a community invite: its name: %r" % more[4])
ok(more[5].get("name") is None, "a protected community name: none: %r" % more[5])
ok(more[6].get("name") == "Finders g1", "the community-finder invite: only the approved one not joined yet: %r" % more[6])
ok(all(d.get("hold") == 5 and d.get("v") == "chat43" for d in more), "each with hold and chat43")
raw = json.dumps(more)
ok("|K" not in raw and "#" not in raw, "never a |K string or a whole BattleTag: %s" % raw)
ok(not any(d.get("type") == "chat" and ("LFG Deadmines" in json.dumps(d)) for d in docs(L)[n1:]), "a broadcast is not a chat line")
L.execute('CVARS.toastDuration = nil; fire("BN_FRIEND_INVITE_ADDED")')
last = [d for d in docs(L) if d.get("type") == "notice" and d.get("kind") == "bnet"][-1]
ok(last.get("t") == "invite" and last.get("hold") is None, "no toastDuration readable: no hold (the app keeps the game's 4 s)")
L.execute('CVARS.toastDuration = "5"')

# ---- 4. the "!" press: chcmd column 1
cell = 'WowCompanion.pad.rows.chcmd.cells[1]'
ok(L.eval('WowCompanion.pad.rows.chcmd ~= nil and WowCompanion.pad.rows.chcmd.n') == 4, "chcmd has four cells (no new row; chat43: 2 / 3 the UTF-8 byte prefixes)")
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
