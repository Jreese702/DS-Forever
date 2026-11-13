"""hub53 THE PARTY TARGET HIGHLIGHT (WowCompanionHub/Hub.lua hub.phlApply), off-device: Hub.lua under a stub game.

The user, 2026-11-13: "there is a bug when targeting a party member, there is a highlight where the old raid frames used to be at."

The party group is hidden by alpha on CompactPartyFrame / PartyFrame (hub19). The game's raid-style party buttons carry regions marked
ignoreParentAlpha (CompactUnitFrameTemplate: selectionHighlight, readyCheckIcon and its Icon), the classic-style party member its
NotPresentIcon, and the gamepad's group targeting draws its TargetHighlight on a parent of its own. The stub models each region's
effective alpha (its own times its parent's, unless it ignores the parent's) and the game's own update functions (Show / Hide only,
as compactunitframe.lua:894-906 does), then checks that targeting a party member - out of a fight and in one, by the update the game
runs on any target change - leaves every piece of the hidden frames invisible, that the shown group shows the highlight again, that
nothing secure is shown / hidden / moved / re-parented by us, and that a nameplate's own highlight (the same update function) is
untouched.

    python tools/test_party_target_highlight.py

Needs lupa. Exit code 1 on any failure."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import test_compass as tc  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HUB = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons", "WowCompanionHub", "Hub.lua")

STUB = r'''
SENT, ROWS = {}, {}
SECURE_CALLS = 0              -- Show / Hide / SetParent / SetPoint / ClearAllPoints on a secure frame while WE run
OURS = false                  -- true while the add-on's code runs (a tap, a hook), false while the "game" runs
COMBAT = false
local Frame = {}
local ABSENT = { UpdateSystemSettingOpacity = true, ApplyAlpha = true, OnEvent = true, OnUpdate = true }
local function noop() end
Frame.__index = function(t, k) local v = rawget(Frame, k); if v ~= nil then return v end; if ABSENT[k] then return nil end; return noop end
local function guard(self, what) if OURS and rawget(self, "secure") then SECURE_CALLS = SECURE_CALLS + 1; LAST_SECURE = what end end
function Frame:SetAlpha(a) self.alpha = a end
function Frame:GetAlpha() return self.alpha or 1 end
function Frame:Show()
    guard(self, "Show")
    local was = self.shown
    self.shown = true
    local sc = rawget(self, "scripts")
    if not was and sc and sc.OnShow then for _, fn in ipairs(sc.OnShow) do fn(self) end end
end
function Frame:Hide() guard(self, "Hide"); self.shown = false end
function Frame:SetShown(v) if v then self:Show() else self:Hide() end end
function Frame:IsShown() return self.shown == true end
function Frame:SetParent(p) guard(self, "SetParent"); self.parent = p end
function Frame:GetParent() return rawget(self, "parent") end
function Frame:SetPoint() guard(self, "SetPoint") end
function Frame:ClearAllPoints() guard(self, "ClearAllPoints") end
function Frame:HookScript(k, fn) self.scripts = rawget(self, "scripts") or {}; self.scripts[k] = self.scripts[k] or {}; table.insert(self.scripts[k], fn) end
function Frame:RegisterEvent() return true end
function Frame:SetScript(k, fn) self[k] = fn end
function Frame:SetIgnoreParentAlpha(v) self.ignore = v end
function CreateFrame() return setmetatable({ shown = true }, Frame) end
function MK(parent, o) o = o or {}; o.parent = parent; if o.shown == nil then o.shown = true end; return setmetatable(o, Frame) end
-- what the screen shows of a region: shown all the way up, and its alpha times its parents' (an ignoreParentAlpha region stops there)
function VIS(r)
    local a, f, own = 1, r, true
    while f do
        if not rawget(f, "shown") then return 0 end
        if own then a = a * (rawget(f, "alpha") or 1) end
        if rawget(f, "ignore") then own = false end
        f = rawget(f, "parent")
    end
    return a
end
-- the post-hooks, as the game's: run after the original, with its arguments (the add-on's code: OURS on)
function hooksecurefunc(a, b, c)
    local tbl, name, fn = _G, a, b
    if type(a) == "table" then tbl, name, fn = a, b, c end
    local orig = tbl[name]
    tbl[name] = function(...)
        local r = { orig(...) }
        local was = OURS; OURS = true; fn(...); OURS = was
        return (table.unpack or unpack)(r)
    end
end
UIParent = MK(nil, {})
-- the raid-style party frame: CompactPartyFrameMember1..5 / Pet1..5 (CompactRaidGroupTemplate + compactpartyframe.xml)
CompactPartyFrame = MK(UIParent, { secure = false, memberUnitFrames = {}, petUnitFrames = {} })
local function cuf(name, unit)
    local b = MK(CompactPartyFrame, { secure = true, unit = unit, displayedUnit = unit, optionTable = { displaySelectionHighlight = true } })
    b.selectionHighlight = MK(b, { ignore = true, shown = false })         -- compactunitframe.xml:50 ignoreParentAlpha
    b.aggroHighlight = MK(b, { shown = false })                            -- :47 (takes the parent's alpha)
    b.readyCheckIcon = MK(b, { ignore = true, shown = false })             -- :87 ignoreParentAlpha
    b.readyCheckIcon.Icon = MK(b.readyCheckIcon, { ignore = true })        -- :90 ignoreParentAlpha
    b.healthBar = MK(b, {})
    _G[name] = b
    return b
end
for i = 1, 5 do
    table.insert(CompactPartyFrame.memberUnitFrames, cuf("CompactPartyFrameMember" .. i, i == 1 and "player" or ("party" .. (i - 1))))
    table.insert(CompactPartyFrame.petUnitFrames, cuf("CompactPartyFramePet" .. i, nil))
end
-- the classic-style party frame (PartyFrame.MemberFrame1..4; NotPresentIcon ignoreParentAlpha, partyframetemplates.xml:342)
PartyFrame = MK(UIParent, {})
for i = 1, 4 do
    local m = MK(PartyFrame, { secure = true, unit = "party" .. i })
    m.NotPresentIcon = MK(m, { ignore = true, shown = false })
    m.Flash = MK(m, { shown = false })
    PartyFrame["MemberFrame" .. i] = m
end
CompactRaidFrameManager = MK(UIParent, {})
-- the gamepad's group targeting (grouptargeting.lua:1, :25): its box on its own parent
GroupTargeting = MK(UIParent, {})
GroupTargeting.TargetHighlight = MK(GroupTargeting, { shown = false })
-- a nameplate: the same update function, its own ignoreParentAlpha highlight (never ours to touch)
PLATE = MK(UIParent, { secure = false, unit = "nameplate1", displayedUnit = "nameplate1", optionTable = { displaySelectionHighlight = true } })
PLATE.selectionHighlight = MK(PLATE, { ignore = true, shown = false })
for _, n in ipairs({ "PlayerFrame", "PetFrame", "TargetFrame", "TargetFrameToT" }) do _G[n] = MK(UIParent, {}) end

TARGET, RAID = nil, false
function UnitIsUnit(a, b) return a ~= nil and b == "target" and a == TARGET end
function IsInRaid() return RAID end
function InCombatLockdown() return COMBAT end
READY = {}
function GetReadyCheckStatus(u) return READY[u] end
-- the game's own update functions (compactunitframe.lua:894-906, :1367; Show / Hide only, never alpha)
function CompactUnitFrame_UpdateSelectionHighlight(frame)
    if UnitIsUnit(frame.displayedUnit, "target") then frame.selectionHighlight:Show() else frame.selectionHighlight:Hide() end
end
function CompactUnitFrame_UpdateReadyCheck(frame)
    if GetReadyCheckStatus(frame.unit) then frame.readyCheckIcon:Show() else frame.readyCheckIcon:Hide() end
end
-- PLAYER_TARGET_CHANGED as the game runs it: every compact button and plate updates its highlight
function TARGET_CHANGED(unit)
    TARGET = unit
    for _, b in ipairs(CompactPartyFrame.memberUnitFrames) do CompactUnitFrame_UpdateSelectionHighlight(b) end
    for _, b in ipairs(CompactPartyFrame.petUnitFrames) do CompactUnitFrame_UpdateSelectionHighlight(b) end
    CompactUnitFrame_UpdateSelectionHighlight(PLATE)
end
SlashCmdList = {}
function GetTime() return 100 end
function GetCurrentKeyBoardFocus() return nil end
WowCompanion = {
    S = function(fn) local ok, v = pcall(fn); if ok then return v end end,
    send = function(j) SENT[#SENT + 1] = j end, encode = function(t) return t end, push = function() end,
    pad = { op = function(name, ns, fn) ROWS[name] = fn; return #ROWS + 1 end, describe = function() return {} end },
    snapshot = function() end,
}
'''


def main():
    fails = []

    def chk(name, ok):
        print(("PASS " if ok else "FAIL ") + name)
        if not ok:
            fails.append(name)

    L = tc.lupa.LuaRuntime()
    L.execute(STUB)
    src = open(HUB, encoding="utf-8").read()
    L.execute("OURS = true")
    L.execute(src.replace("local ADDON = ...", "local ADDON = 'WowCompanionHub'", 1))
    L.execute("OURS = false")
    G = L.globals()
    hub = G.WowCompanion.hub
    SW = hub.SW
    chk("hub53 loaded: hub.phlApply, the party highlight list", hub.phlApply is not None and hub.PHL is not None)

    def tap(col):
        L.execute("OURS = true")
        r = hub.swTap(col)
        L.execute("OURS = false")
        return r

    vis = G.VIS

    def every_piece():
        """(name, effective alpha) of every piece of the game's party frames"""
        out = []
        cpf = G.CompactPartyFrame
        for arr in ("memberUnitFrames", "petUnitFrames"):
            t = cpf[arr]
            for i in range(1, len(t) + 1):
                b = t[i]
                for k in ("selectionHighlight", "aggroHighlight", "readyCheckIcon", "healthBar"):
                    out.append(("%s%d.%s" % (arr, i, k), vis(b[k])))
                out.append(("%s%d.readyCheckIcon.Icon" % (arr, i), vis(b.readyCheckIcon.Icon)))
                out.append(("%s%d" % (arr, i), vis(b)))
        for i in range(1, 5):
            m = G.PartyFrame["MemberFrame%d" % i]
            for k in ("NotPresentIcon", "Flash"):
                out.append(("MemberFrame%d.%s" % (i, k), vis(m[k])))
            out.append(("MemberFrame%d" % i, vis(m)))
        return out

    def lit():
        return [(n, a) for n, a in every_piece() if a > 0]

    hl2 = G.CompactPartyFrameMember3.selectionHighlight          # party2's button
    # the game's own party frames chosen (our party rows off: hub26 hides the game's while ours are on, their default)
    tap(SW.UFP_OFF)
    tap(SW.PARTY_SHOW)

    # THE BUG, AS SEEN: the party group shown, a party member targeted: the border is drawn (the game's normal look)
    G.TARGET_CHANGED("party2")
    chk("party shown, party2 targeted: the game's border on its button (vis %s)" % vis(hl2), vis(hl2) == 1)

    # the party group hidden (the app's tap), a party member targeted by the game's own update (any route: our Party mode tap, the
    # game's cycling, a click): every piece invisible
    tap(SW.PARTY_HIDE)
    chk("party hidden: the frames at alpha 0 (CompactPartyFrame %s, PartyFrame %s)" % (G.CompactPartyFrame.alpha, G.PartyFrame.alpha),
        G.CompactPartyFrame.alpha == 0 and G.PartyFrame.alpha == 0)
    G.TARGET_CHANGED("party1")
    chk("party hidden, party1 targeted out of a fight: every piece invisible (lit %s)" % lit(), lit() == [])
    chk("... the border itself shown by the game (IsShown %s) but at alpha %s" % (G.CompactPartyFrameMember2.selectionHighlight.shown,
        G.CompactPartyFrameMember2.selectionHighlight.alpha),
        G.CompactPartyFrameMember2.selectionHighlight.shown is True and vis(G.CompactPartyFrameMember2.selectionHighlight) == 0)

    # without hub53 (the regression it fixes): the same frames, the region alpha put back to 1 -> the border shows through alpha 0
    G.CompactPartyFrameMember2.selectionHighlight.alpha = 1
    chk("regression model: with its own alpha 1 the border shows over the hidden frame (vis %s) - the user's report"
        % vis(G.CompactPartyFrameMember2.selectionHighlight), vis(G.CompactPartyFrameMember2.selectionHighlight) == 1)
    G.TARGET_CHANGED("party1")                                   # the game's update again: our post-hook puts the 0 back
    chk("... the next game update puts it back to 0 (post-hook): lit %s" % lit(), lit() == [])

    # IN A FIGHT: the same, and nothing secure shown / hidden / moved / re-parented by us
    G.COMBAT = True
    G.SECURE_CALLS = 0
    for u in ("party2", "party3", "party4", "player", "party1"):
        G.TARGET_CHANGED(u)
    tap(SW.PARTY_HIDE)
    chk("in a fight, targets cycled through the party: every piece invisible (lit %s)" % lit(), lit() == [])
    chk("in a fight: no Show / Hide / SetParent / SetPoint on a secure frame by the add-on (%s, last %s)" % (G.SECURE_CALLS, G.LAST_SECURE),
        G.SECURE_CALLS == 0)
    # a ready check in a fight
    G.READY["party1"] = "waiting"
    G.CompactUnitFrame_UpdateReadyCheck(G.CompactPartyFrameMember2)
    chk("party hidden, a ready check: the icon and its Icon invisible (%s, %s)" % (vis(G.CompactPartyFrameMember2.readyCheckIcon),
        vis(G.CompactPartyFrameMember2.readyCheckIcon.Icon)),
        G.CompactPartyFrameMember2.readyCheckIcon.shown is True and vis(G.CompactPartyFrameMember2.readyCheckIcon) == 0
        and vis(G.CompactPartyFrameMember2.readyCheckIcon.Icon) == 0)
    # the classic-style party member's not-present eye (the game shows it by itself)
    G.PartyFrame.MemberFrame2.NotPresentIcon.shown = True
    chk("party hidden: the classic party member's NotPresentIcon invisible (%s)" % vis(G.PartyFrame.MemberFrame2.NotPresentIcon),
        vis(G.PartyFrame.MemberFrame2.NotPresentIcon) == 0)
    G.COMBAT = False

    # the gamepad's group targeting box (hover mode): hidden with the party, shown in a raid (the raid frames are the game's)
    L.execute("GroupTargeting.TargetHighlight:Show()")
    gth = G.GroupTargeting.TargetHighlight
    chk("party hidden: the gamepad group-targeting box invisible as it shows (vis %s)" % vis(gth), vis(gth) == 0)
    L.execute("GroupTargeting.TargetHighlight:Hide()")
    G.RAID = True
    L.execute("GroupTargeting.TargetHighlight:Show()")
    chk("in a raid: the gamepad box drawn (vis %s): the raid frames are never touched" % vis(gth), vis(gth) == 1)
    L.execute("GroupTargeting.TargetHighlight:Hide()")
    G.RAID = False

    # a nameplate's own highlight (the same update function): never ours
    G.TARGET_CHANGED("nameplate1")
    pa = L.eval('rawget(PLATE.selectionHighlight, "alpha")')
    chk("a targeted nameplate keeps its own highlight (vis %s, its alpha never set: %s)" % (vis(G.PLATE.selectionHighlight), pa),
        vis(G.PLATE.selectionHighlight) == 1 and pa is None)

    # SHOWN AGAIN: the border back on the targeted member, nothing else lit by us
    tap(SW.PARTY_SHOW)
    G.TARGET_CHANGED("party3")
    hl4 = G.CompactPartyFrameMember4.selectionHighlight
    chk("party shown again, party3 targeted: its border drawn (vis %s), the ready check icon back (%s)" % (vis(hl4),
        vis(G.CompactPartyFrameMember2.readyCheckIcon.Icon)),
        vis(hl4) == 1 and vis(G.CompactPartyFrameMember2.readyCheckIcon.Icon) == 1 and vis(G.PartyFrame.MemberFrame2.NotPresentIcon) == 1)
    L.execute("GroupTargeting.TargetHighlight:Show()")
    chk("party shown: the gamepad box drawn (vis %s)" % vis(gth), vis(gth) == 1)
    L.execute("GroupTargeting.TargetHighlight:Hide()")

    # hub26: OUR party frames on (the party switch left at shown): the game's hidden with every piece
    tap(SW.UFP_ON)
    G.TARGET_CHANGED("party2")
    chk("our own party frames on (their default), the game's switch at shown: every piece of the game's invisible (lit %s)" % lit(), lit() == [])
    tap(SW.UFP_OFF)

    # REVIEW: the raid-style party frame made AFTER the last apply (CompactPartyFrame_Generate runs when those frames first come up):
    # the game's own update learns the new buttons by their parent - no tap needed - and a plate is still never touched
    tap(SW.PARTY_HIDE)
    L.execute("""
        local old = CompactPartyFrame
        CompactPartyFrame = MK(PartyFrame, { secure = false, memberUnitFrames = {}, petUnitFrames = {} })
        for i = 1, 5 do
            local b = MK(CompactPartyFrame, { secure = true, unit = i == 1 and "player" or ("party" .. (i - 1)), optionTable = {} })
            b.displayedUnit = b.unit
            b.selectionHighlight = MK(b, { ignore = true, shown = false })
            b.readyCheckIcon = MK(b, { ignore = true, shown = false })
            b.readyCheckIcon.Icon = MK(b.readyCheckIcon, { ignore = true })
            CompactPartyFrame.memberUnitFrames[i] = b
        end
        CompactPartyFrame.alpha = 0
    """)
    G.TARGET_CHANGED("party2")
    late = G.CompactPartyFrame.memberUnitFrames[3].selectionHighlight
    chk("a party frame made after the last apply, party2 targeted: its border invisible with no tap (vis %s)" % vis(late),
        late.shown is True and vis(late) == 0)
    tap(SW.PARTY_SHOW)
    G.TARGET_CHANGED("party2")
    chk("... and drawn once the party is shown again (vis %s)" % vis(late), vis(late) == 1)

    # the version tag and the header note
    chk("hub53: the version tag and the header note quote the user", 'local HV = "hub53"' in src
        and "there is a bug when targeting a party member, there is a highlight where the" in src and "hub53 THE PARTY TARGET HIGHLIGHT" in src)
    # leave no trace: no CVar / binding / macro writes in the new block
    blk = src[src.index("\n-- hub53 THE PARTY TARGET HIGHLIGHT"):src.index('-- hub17: one switch column of the row "uisw"')]
    blk = "\n".join(ln.split("--")[0] for ln in blk.splitlines())          # the code, not its comments
    chk("hub53's block: alpha only (no SetCVar / SetBinding / macro / Show / Hide / SetParent / SetPoint calls)",
        not any(w in blk for w in ("SetCVar", "SetBinding", "CreateMacro", "EditMacro", ":Show(", ":Hide(", "SetParent(", "SetPoint(",
                                    ".Show,", ".Hide,", ".SetParent,", ".SetPoint,")))

    print("\n%d failure(s)" % len(fails) if fails else "\nALL PASS")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
