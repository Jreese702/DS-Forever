"""Buff tooltips with the game's tooltips off (Trail.lua's WC.tips, tt4), off-device: the tips section of Trail.lua run alone in a model
of this client's tooltip machinery, read from its own UI source (E:/wc-scratch-c9979a42/ui_all/interface/addons):

    python -B tools/test_bufftips.py                    the current Trail.lua: every check must pass
    python -B tools/test_bufftips.py --trail FILE       another Trail.lua (the base 1d1906b2 one shows the causes: it fails)

The user, 2026-11-16: "tooltips for buffs are still popping up on the top screen even though they are off". The model:
  - the game's buff tooltips: hovering a buff (AuraButtonMixin:OnEnter -> GameTooltip, refilled every 0.2 s by its UpdateTooltip), the
    controller-mode AUTO tooltip at every buff gained (BuffFrameMixin:ShowNextAuraForTooltip -> BuffFrameTooltip, 4 s), and the
    target frame's buffs (TargetFrameAuraButtonTemplate -> AuraButtonTooltip: FORBIDDEN, hidden from _G, its own method table);
  - review of tt4: the pet / party buff list (PetFrameMixin / PartyMemberFrameMixin:OnEnter -> PartyMemberBuffTooltip, a PLAIN frame
    at TOOLTIP strata, not the tooltip type) and the consolidated buffs' list (BuffFrame.ConsolidatedBuffs.Tooltip, plain, no name);
  - Hub's riders: the game's BuffFrame, TargetFrame and PetFrame at alpha 0, still taking the mouse (an invisible frame keeps its hover);
  - the mouse: hover = the topmost shown frame with MOTION enabled under the cursor, a click = the topmost with CLICKS enabled
    (the client's split: SetMouseMotionEnabled / SetMouseClickEnabled, as TapPad's sink uses it);
  - the engine's alpha put-back after a tooltip is filled (tt3 measured one with no Lua call), three ways:
      none      only SetOwner's own (synchronous, a post-hook sees it);
      deferred  the next frame, before its OnUpdate scripts;
      late      at the layout, after OnSizeChanged, before the draw (the worst case: nothing in Lua runs after it);
    and the type's shared method table hookable or NOT (a write to it fails).
A tooltip DRAWS in a frame when it is shown with an effective alpha above 0 at the draw. THE CAUSES (run with --trail on the base 1d1906b2
Trail.lua, tips tt3): BuffFrameTooltip draws for its whole 4 s in every mode but "none + hookable" - the one world where tt3 would hold
it, which the user's report rules out - while GameTooltip passes everywhere (its named OnShow / OnUpdate holds); AuraButtonTooltip draws
in every mode (forbidden: no hold of ours reaches it). Checks, for every mode: with tooltips on the model shows all three (so it
reproduces the popups); with them off none draws ("held at 0": no frame drawn; in the late mode at most each fill's own frame for a
hookable one, what GameTooltip already had), the target's buff never gets its hover, a click still reaches it, the bottom card's read
still has the lines, off again gives every alpha and hover back; the add-on never touches a forbidden object, calls only reads and
SetAlpha / HookScript on the game's frames, never makes a frame that takes clicks, writes no CVar. Needs lupa. Exit 1 on a failure."""
import os
import re
import sys

import lupa

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TRAIL = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons", "WowCompanion", "Trail.lua")

MODEL = r'''
unpack = unpack or table.unpack
SECURE = 0                    -- > 0 while the game's own code runs (a forbidden object answers only then)
ERRS = {}                     -- forbidden accesses and blocked calls, by the add-on
ADDON_CALLS = {}              -- method -> count: what the add-on called on the GAME's frames
NOW = 100; DT = 1 / 30
function GetTime() return NOW end
CVAR_WRITES = 0
function SetCVar() CVAR_WRITES = CVAR_WRITES + 1 end
C_CVar = { SetCVar = SetCVar, GetCVar = function() return nil end }
FIGHT = false
function InCombatLockdown() return FIGHT end
function issecretvalue(v) return false end
STRATA = { BACKGROUND = 1, LOW = 2, MEDIUM = 3, HIGH = 4, DIALOG = 5, FULLSCREEN = 6, FULLSCREEN_DIALOG = 7, TOOLTIP = 8 }
ALL = {}; EVENTS = {}; TIMERS = {}
local SEQ = 0

-- scripts: the frame's own (the game's: run as the game) then the hooks (always insecure, as HookScript's)
function fire(o, ev, ...)
    local was = SECURE
    local s = o.scripts[ev]
    if s then
        SECURE = o.game and 1 or 0                       -- the game's own script runs as the game, ours never does
        local ok, e = pcall(s, o, ...)
        if not ok then ERRS[#ERRS + 1] = "script " .. ev .. ": " .. tostring(e) end
    end
    local h = o.hooks[ev]
    if h then
        SECURE = 0
        for _, fn in ipairs(h) do
            local ok, e = pcall(fn, o, ...)
            if not ok then ERRS[#ERRS + 1] = "hook " .. ev .. ": " .. tostring(e) end
        end
    end
    SECURE = was
end
function VIS(o) while o do if not o.shown then return false end; o = o.parent end; return true end   -- the engine's own view
function game(fn, ...)                 -- the game's own code
    SECURE = SECURE + 1
    local ok, e = pcall(fn, ...)
    SECURE = SECURE - 1
    if not ok then ERRS[#ERRS + 1] = "game: " .. tostring(e) end
end
function hooksecurefunc(t, k, fn)
    if type(t) == "string" then t, k, fn = _G, t, k end
    local orig = t[k]
    if type(orig) ~= "function" then error("hooksecurefunc: no function " .. tostring(k)) end
    t[k] = function(...)
        local r = { orig(...) }
        local was = SECURE; SECURE = 0
        pcall(fn, ...)
        SECURE = was
        return unpack(r)
    end
end
C_Timer = { After = function(d, fn) TIMERS[#TIMERS + 1] = { at = NOW + d, fn = fn } end }

-- ---------------------------------------------------------------- the widget types: ONE method table per type, as the client's
local Frame = {}
function Frame:GetObjectType() return self.kind end
function Frame:IsForbidden() return self.forbidden == true end
function Frame:GetName() return self.name end
function Frame:IsShown() return self.shown == true end
function Frame:IsVisible()
    local o = self
    while o do if not o.shown then return false end; o = o.parent end
    return true
end
function Frame:Show() if not self.shown then self.shown = true; fire(self, "OnShow") end end
function Frame:Hide() if self.shown then self.shown = false; fire(self, "OnHide") end end
function Frame:SetShown(v) if v then self:Show() else self:Hide() end end
function Frame:SetAlpha(a) self.alpha = a end
function Frame:GetAlpha() return self.alpha end
function Frame:SetScript(k, fn) self.scripts[k] = fn end
function Frame:GetScript(k) return self.scripts[k] end
function Frame:HookScript(k, fn) local h = self.hooks[k] or {}; h[#h + 1] = fn; self.hooks[k] = h end
function Frame:RegisterEvent(e) EVENTS[e] = EVENTS[e] or {}; table.insert(EVENTS[e], self) end
function Frame:SetFrameStrata(s) self.strata = s end
function Frame:GetFrameStrata() return self.strata end
function Frame:SetFrameLevel(l) self.level = l end
function Frame:GetFrameLevel() return self.level end
function Frame:ClearAllPoints() self.rect, self.anchor = nil, nil end
function Frame:SetAllPoints(o) self.anchor = o; self.rect = nil end
function Frame:SetPoint(p, rel, rp, x, y) self.anchor = nil; self.rect = { x or 0, y or 0, self.rect and self.rect[3] or 0, self.rect and self.rect[4] or 0 } end
function Frame:SetSize(w, h) local r = self.rect or { 0, 0, 0, 0 }; self.rect = { r[1], r[2], w, h } end
function Frame:GetRect() local r = RECT(self); if r then return r[1], r[2], r[3], r[4] end end
function Frame:GetEffectiveScale() return 1 end
function Frame:EnableMouse(on) self.motion = on; self.click = on end
function Frame:SetMouseMotionEnabled(on) self.motion = on end
function Frame:SetMouseClickEnabled(on) self.click = on end
function RECT(o)
    if o.anchor then return RECT(o.anchor) end
    return o.rect
end
-- the tooltip type (GameTooltip): its C methods. SetOwner puts the alpha back (tt3's measurement); a fill marks it for the layout
local Tip = {}
for k, v in pairs(Frame) do Tip[k] = v end
function Tip:SetOwner(o) self.owner = o; self.alpha = 1; self.lines = {}; self.dirty = true end
function Tip:ClearLines() self.lines = {}; self.dirty = true end
function Tip:AddLine(t) self.lines[#self.lines + 1] = t; self.dirty = true end
function Tip:AddDoubleLine(a, b) self.lines[#self.lines + 1] = a .. " " .. b; self.dirty = true end
function Tip:AppendText(t) self.dirty = true end
function Tip:SetText(t) self.lines = { t }; self.dirty = true end
function Tip:NumLines() return #self.lines end
function Tip:IsOwned(o) return self.owner == o end
function Tip:GetOwner() return self.owner end
function Tip:Show() Frame.Show(self); self.dirty = true end
function Tip:Hide() Frame.Hide(self); self.owner = nil end
local Button = {}
for k, v in pairs(Frame) do Button[k] = v end
-- every method records the add-on's calls on the game's frames (taken before any hook)
local function watch(tbl)
    for k, fn in pairs(tbl) do
        tbl[k] = function(self, ...)
            if SECURE == 0 and type(self) == "table" and self.game then ADDON_CALLS[k] = (ADDON_CALLS[k] or 0) + 1 end
            return fn(self, ...)
        end
    end
end
watch(Frame); watch(Tip); watch(Button)
-- a FORBIDDEN object has its own method table (the raw functions, never the public table's hooks), answering only the game's code
local function forbiddenTable(src)
    local t = {}
    for k, fn in pairs(src) do
        t[k] = function(self, ...)
            if SECURE == 0 and k ~= "IsForbidden" then
                ERRS[#ERRS + 1] = "forbidden " .. k
                error("Attempt to access forbidden object from code tainted by an AddOn")
            end
            return fn(self, ...)
        end
    end
    return t
end
FORBID = { GameTooltip = forbiddenTable(Tip), Button = forbiddenTable(Button) }
-- the shared table as the client hands it out (getmetatable(GameTooltip).__index); TYPE_HOOKS false = a write to it fails
TYPES = { Frame = Frame, Button = Button, GameTooltip = Tip, AuraContainer = Frame }
function SEAL()
    local proxy = setmetatable({}, { __index = Tip, __newindex = function() error("method table is read-only") end })
    TYPES.GameTooltip = proxy
end
local function new(kind, name, parent, o)
    SEQ = SEQ + 1
    o = o or {}
    o.kind, o.name, o.parent, o.seq = kind, name, parent, SEQ
    o.scripts, o.hooks = o.scripts or {}, {}
    if o.alpha == nil then o.alpha = 1 end
    if o.shown == nil then o.shown = true end
    o.strata = o.strata or (parent and parent.strata) or "MEDIUM"
    o.level = o.level or ((parent and parent.level or 0) + 1)
    local idx = o.forbidden and FORBID[kind] or TYPES[kind]
    setmetatable(o, { __index = idx })
    ALL[#ALL + 1] = o
    return o
end
function CreateFrame(kind, name, parent)
    local o = new(kind, name, parent or UIParent, {})
    if name then _G[name] = o end
    return o
end

-- ---------------------------------------------------------------- the game's frames (built by BUILD after SEAL / not)
function BUILD()
    UIParent = new("Frame", "UIParent", nil, { game = true, rect = { 0, 0, 1021, 574 }, strata = "MEDIUM", level = 0 })
    -- GameTooltipTemplate's data accessor (a Lua mixin copied onto each tooltip): ProcessInfo -> ClearLines, the lines, Show
    local function accessor(self, unit, id)
        self:ClearLines(); self:AddLine("Power Word: Fortitude"); self:AddLine("Stamina increased by 3."); self:Show()
    end
    GameTooltip = new("GameTooltip", "GameTooltip", UIParent, { game = true, shown = false, strata = "TOOLTIP", rect = { 800, 80, 200, 60 } })
    GameTooltip.SetUnitAuraByAuraInstanceID = accessor
    GameTooltip.timer = 0
    GameTooltip.scripts.OnUpdate = function(self, el)            -- GameTooltip_OnUpdate: the owner's UpdateTooltip every 0.2 s
        self.timer = self.timer - el
        if self.timer > 0 then return end
        self.timer = 0.2
        local o = self:GetOwner()
        if o and o.UpdateTooltip then o:UpdateTooltip() end
    end
    BuffFrameTooltip = new("GameTooltip", "BuffFrameTooltip", UIParent, { game = true, shown = false, strata = "TOOLTIP", rect = { 700, 450, 200, 60 } })
    BuffFrameTooltip.SetUnitAuraByAuraInstanceID = accessor
    -- AuraButtonTooltip: forbidden, hideFromGlobalEnv (never in _G)
    AURATIP = new("GameTooltip", "AuraButtonTooltip", UIParent, { game = true, forbidden = true, shown = false, strata = "TOOLTIP", rect = { 600, 200, 200, 60 } })
    AURATIP.ShowAuraTooltip = function(self) self:ClearLines(); self:AddLine("Thorns"); self:Show() end
    -- the player's buffs, top right, alpha 0 (Hub's riders)
    BuffFrame = new("Frame", "BuffFrame", UIParent, { game = true, alpha = 0, strata = "LOW", level = 10, rect = { 700, 520, 300, 40 } })
    BUFFBTN = new("Button", nil, BuffFrame, { game = true, motion = true, click = true, rect = { 960, 525, 30, 30 } })
    BUFFBTN.scripts.OnEnter = function(self)
        GameTooltip:SetOwner(self); GameTooltip:SetFrameLevel(12); GameTooltip:SetUnitAuraByAuraInstanceID("player", 7)
    end
    BUFFBTN.scripts.OnLeave = function(self) GameTooltip:Hide() end
    BUFFBTN.UpdateTooltip = function(self) if GameTooltip:IsOwned(self) then GameTooltip:SetUnitAuraByAuraInstanceID("player", 7) end end
    -- a plain game frame the add-on must never dim
    Minimap = new("Frame", "Minimap", UIParent, { game = true, strata = "LOW", rect = { 880, 420, 140, 140 } })
    -- the target frame (LOW, level 500), alpha 0 (Hub), its aura container (public) and a buff button in it (forbidden, secure env)
    TargetFrame = new("Button", "TargetFrame", UIParent, { game = true, alpha = 0, strata = "LOW", level = 500, motion = true, click = true,
                                                           rect = { 700, 300, 230, 100 } })
    TargetFrame.scripts.OnClick = function() TARGET_CLICKS = (TARGET_CLICKS or 0) + 1 end
    local content = { TargetFrameContentContextual = {} }
    TargetFrame.TargetFrameContent = content
    AURAS = new("AuraContainer", nil, TargetFrame, { game = true, level = 501, rect = { 710, 260, 160, 40 } })
    content.TargetFrameContentContextual.Auras = AURAS
    TAURA = new("Button", nil, AURAS, { game = true, forbidden = true, motion = true, click = true, level = 502, rect = { 712, 262, 20, 20 } })
    TAURA.scripts.OnEnter = function(self) ENTERS = (ENTERS or 0) + 1; AURATIP:SetOwner(self); AURATIP:ShowAuraTooltip() end
    TAURA.scripts.OnLeave = function(self) AURATIP:Hide() end
    TAURA.scripts.OnClick = function() AURA_CLICKS = (AURA_CLICKS or 0) + 1 end
    -- review of tt4: the pet's frame (Hub: alpha 0, still takes the mouse) and the game's pet / party buff list, PartyMemberBuffTooltip:
    -- a PLAIN frame (not the tooltip type: no type hook ever meets it), TOOLTIP strata, no parent; shown at the unit frame's OnEnter
    -- (PetFrameMixin:OnEnter -> UnitFrame_OnEnter's GameTooltip, then PartyMemberBuffTooltip:SetPoint + UpdateTooltip -> Show)
    PMBT = new("Frame", "PartyMemberBuffTooltip", nil, { game = true, shown = false, strata = "TOOLTIP", motion = true, click = true,
                                                        rect = { 160, 380, 60, 30 } })
    PartyMemberBuffTooltip = PMBT
    PetFrame = new("Button", "PetFrame", UIParent, { game = true, alpha = 0, strata = "LOW", level = 5, motion = true, click = true,
                                                     rect = { 60, 420, 100, 40 } })
    PetFrame.scripts.OnEnter = function(self)
        GameTooltip:SetOwner(self); GameTooltip:SetText("Wolf"); GameTooltip:Show()
        PMBT:SetPoint("TOPLEFT", self, "TOPLEFT", 160, 380); PMBT:SetSize(60, 30); PMBT:Show()
    end
    PetFrame.scripts.OnLeave = function(self) GameTooltip:Hide(); PMBT:Hide() end
    PetFrame.scripts.OnClick = function() PET_CLICKS = (PET_CLICKS or 0) + 1 end
    -- the consolidated buffs' list: BuffFrame.ConsolidatedBuffs.Tooltip, a plain frame with no global name, TOOLTIP strata
    CBTN = new("Button", nil, BuffFrame, { game = true, motion = true, click = true, level = 11, rect = { 930, 525, 25, 30 } })
    BuffFrame.ConsolidatedBuffs = CBTN
    CBT = new("Frame", "ConsolidatedBuffs.Tooltip", CBTN, { game = true, shown = false, strata = "TOOLTIP", rect = { 820, 460, 100, 50 } })
    CBTN.Tooltip = CBT
    CBTN.scripts.OnEnter = function(self) CBT:Show() end
    CBTN.scripts.OnLeave = function(self) CBT:Hide() end
    WowCompanion = { isPlain = function(v) return not issecretvalue(v) end }
    WowCompanionDB = {}
end

-- the game's auto aura tooltip (controller mode): the buff's tooltip at once, hidden 4 s later
function GAIN()
    game(function()
        C_Timer.After(0, function()
            BuffFrameTooltip:SetOwner(BUFFBTN); BuffFrameTooltip:SetFrameLevel(12); BuffFrameTooltip:SetUnitAuraByAuraInstanceID("player", 9)
        end)
        C_Timer.After(4, function() BuffFrameTooltip:Hide() end)
    end)
end

-- ---------------------------------------------------------------- one rendered frame
MODE = "deferred"
CURSOR = { -100, -100 }
DRAWN = {}; FILLS = {}
local motionFocus = nil
local function inside(o, x, y)
    local r = RECT(o)
    return r and x >= r[1] and x <= r[1] + r[3] and y >= r[2] and y <= r[2] + r[4]
end
local function top(field)
    local best
    for _, o in ipairs(ALL) do
        if o[field] and VIS(o) and inside(o, CURSOR[1], CURSOR[2]) then
            if not best or STRATA[o.strata] > STRATA[best.strata]
               or (STRATA[o.strata] == STRATA[best.strata] and (o.level > best.level or (o.level == best.level and o.seq > best.seq))) then
                best = o
            end
        end
    end
    return best
end
function EFFALPHA(o) local a = 1; while o do a = a * o.alpha; o = o.parent end; return a end
local TIPS
function FRAME()
    TIPS = TIPS or { GameTooltip, BuffFrameTooltip, AURATIP, PMBT, CBT }
    NOW = NOW + DT
    local due = {}
    for i = #TIMERS, 1, -1 do if TIMERS[i].at <= NOW then table.insert(due, 1, TIMERS[i]); table.remove(TIMERS, i) end end
    for _, t in ipairs(due) do game(t.fn) end
    for _, t in ipairs(TIPS) do if t.pending then t.pending = false; if t.shown then t.alpha = 1 end end end
    local f = top("motion")
    if f ~= motionFocus then
        if motionFocus then fire(motionFocus, "OnLeave") end
        motionFocus = f
        if f then fire(f, "OnEnter") end
    end
    for _, o in ipairs(ALL) do
        if VIS(o) and (o.scripts.OnUpdate or o.hooks.OnUpdate) then fire(o, "OnUpdate", DT) end
    end
    for _, t in ipairs(TIPS) do
        if t.dirty and t.shown then
            t.dirty = false
            FILLS[t.name] = (FILLS[t.name] or 0) + 1
            fire(t, "OnSizeChanged")
            if MODE == "late" then t.alpha = 1 elseif MODE == "deferred" then t.pending = true end
        elseif t.dirty then t.dirty = false end
    end
    for _, t in ipairs(TIPS) do
        if VIS(t) and EFFALPHA(t) > 0 then DRAWN[t.name] = (DRAWN[t.name] or 0) + 1 end
    end
end
function RUN(secs) for _ = 1, math.floor(secs / DT + 0.5) do FRAME() end end
function CLICK()
    local f = top("click")
    if f then fire(f, "OnClick") end
    return f
end
function EVENT(e) for _, o in ipairs(EVENTS[e] or {}) do fire(o, "OnEvent", e) end end
function RESET() DRAWN = {}; FILLS = {} end
'''


def section(path):
    src = open(path, encoding="utf-8").read()
    a = src.index("-- ================================================================== HIDE THE GAME'S TOOLTIPS")
    b = src.index("-- ==================================================================", a + 10)
    return "local WC = WowCompanion\n" + src[a:b]


def scenario(code, mode, sealed, fails, label):
    L = lupa.LuaRuntime()
    L.execute(MODEL)
    G = L.globals()
    if sealed:
        L.execute("SEAL()")
    L.execute("BUILD()")
    G.MODE = mode
    L.execute(code)
    tag = "[%s, shared table %s]" % (mode, "sealed" if sealed else "hookable")

    def chk(name, ok):
        line = ("PASS " if ok else "FAIL ") + label + " " + tag + " " + name
        print(line)
        if not ok:
            fails.append(line)

    def drawn(n):
        return G.DRAWN[n] or 0

    def fills(n):
        return G.FILLS[n] or 0

    def at(x, y):
        G.CURSOR[1], G.CURSOR[2] = x, y

    T = G.WowCompanion.tips
    L.execute('EVENT("PLAYER_LOGIN"); EVENT("PLAYER_ENTERING_WORLD")')
    late = mode == "late"

    # --- tooltips ON (the game's): the model reproduces all three popups
    at(-100, -100); L.execute("RESET(); GAIN()"); G.RUN(5)
    chk("tooltips on: a buff gained pops BuffFrameTooltip up for its 4 s (%d frames)" % drawn("BuffFrameTooltip"), drawn("BuffFrameTooltip") >= 100)
    at(970, 535); L.execute("RESET()"); G.RUN(1)
    chk("tooltips on: hovering the invisible buff draws GameTooltip (%d frames)" % drawn("GameTooltip"), drawn("GameTooltip") >= 25)
    at(720, 270); L.execute("RESET()"); G.RUN(1)
    chk("tooltips on: hovering the invisible target's buff draws AuraButtonTooltip (%d frames)" % drawn("AuraButtonTooltip"),
        drawn("AuraButtonTooltip") >= 25)
    at(-100, -100); G.RUN(0.2)
    at(100, 440); L.execute("RESET()"); G.RUN(1)
    chk("tooltips on: hovering the invisible pet frame draws PartyMemberBuffTooltip (%d frames)" % drawn("PartyMemberBuffTooltip"),
        drawn("PartyMemberBuffTooltip") >= 25)
    at(-100, -100); G.RUN(0.2)
    L.execute("BuffFrame.alpha = 1")
    at(940, 535); L.execute("RESET()"); G.RUN(1)
    chk("tooltips on: the game's buffs shown, the consolidation button's list draws (%d frames)" % drawn("ConsolidatedBuffs.Tooltip"),
        drawn("ConsolidatedBuffs.Tooltip") >= 25)
    at(-100, -100); G.RUN(0.2)
    L.execute("BuffFrame.alpha = 0")

    # --- tooltips OFF
    T.set(True)
    G.ENTERS = 0
    at(-100, -100); L.execute("RESET(); GAIN()"); G.RUN(5)
    n, k = drawn("BuffFrameTooltip"), fills("BuffFrameTooltip")
    chk("off: a buff gained - BuffFrameTooltip held at 0 (%d frames drawn, %d fills)" % (n, k), n <= (k if late else 0))
    L.execute("RESET(); GAIN()"); G.RUN(0.5); L.execute("GAIN()"); G.RUN(0.5); L.execute("GAIN()"); G.RUN(5)
    n, k = drawn("BuffFrameTooltip"), fills("BuffFrameTooltip")
    chk("off: three buffs in a row (refreshes) - held at 0 (%d frames drawn, %d fills)" % (n, k), n <= (k if late else 0))
    at(970, 535); L.execute("RESET()"); G.RUN(2)
    n, k = drawn("GameTooltip"), fills("GameTooltip")
    chk("off: hovering the invisible buff for 2 s (refilled every 0.2 s) - GameTooltip held at 0 (%d frames drawn, %d fills)" % (n, k),
        n <= (k if late else 0))
    nl = L.eval("#GameTooltip.lines")
    chk("off: the bottom card's read still has the tooltip's lines (%d)" % nl, nl == 2)
    at(720, 270); L.execute("RESET()"); G.RUN(2)
    chk("off: the cursor on the invisible target's buff - AuraButtonTooltip never draws (%d frames)" % drawn("AuraButtonTooltip"),
        drawn("AuraButtonTooltip") == 0)
    chk("off: the buff never got its hover (OnEnter %d)" % G.ENTERS, G.ENTERS == 0)
    G.AURA_CLICKS = 0
    G.CLICK()
    chk("off: a touch there still reaches the target's buff (%d)" % G.AURA_CLICKS, G.AURA_CLICKS == 1)
    G.TARGET_CLICKS = 0
    at(900, 380); G.CLICK()
    chk("off: a touch on the target frame still reaches it (%d)" % G.TARGET_CLICKS, G.TARGET_CLICKS == 1)
    st = T.stats() if T.stats else None
    chk("off: stats say the target frame's shield is up, anchored (%s)" % (dict(st.items()) if st else "no stats"),
        st is not None and st.sh == 1 and st.tm == "anchor")
    # a new target: its buffs appear under a resting cursor
    L.execute('AURAS.shown = false; EVENT("PLAYER_TARGET_CHANGED")')
    at(720, 270); G.RUN(0.5)
    L.execute('RESET(); ENTERS = 0; AURAS.shown = true; EVENT("PLAYER_TARGET_CHANGED")'); G.RUN(2)
    chk("off: a target whose buffs appear under the resting cursor - nothing drawn (%d), no hover (%d)" % (drawn("AuraButtonTooltip"), G.ENTERS),
        drawn("AuraButtonTooltip") == 0 and G.ENTERS == 0)
    # review of tt4: the pet / party buff list and the consolidated list (plain frames, not the tooltip type)
    at(100, 440); L.execute("RESET()"); G.RUN(2)
    n, k = drawn("GameTooltip"), fills("GameTooltip")
    chk("off: hovering the invisible pet frame - PartyMemberBuffTooltip held at 0 (%d frames), its GameTooltip too (%d drawn, %d fills)"
        % (drawn("PartyMemberBuffTooltip"), n, k), drawn("PartyMemberBuffTooltip") == 0 and n <= (k if late else 0))
    G.PET_CLICKS = 0
    G.CLICK()
    chk("off: a touch on the pet frame still reaches it (%d)" % G.PET_CLICKS, G.PET_CLICKS == 1)
    at(-100, -100); G.RUN(0.2)
    L.execute("BuffFrame.alpha = 1")
    at(940, 535); L.execute("RESET()"); G.RUN(2)
    chk("off: the game's buffs shown, the consolidation button's list held at 0 (%d frames)" % drawn("ConsolidatedBuffs.Tooltip"),
        drawn("ConsolidatedBuffs.Tooltip") == 0)
    at(-100, -100); G.RUN(0.2)
    L.execute("BuffFrame.alpha = 0")
    # the same in a fight
    G.FIGHT = True
    at(-100, -100); L.execute("RESET(); GAIN()"); G.RUN(0.5)
    at(720, 270); G.RUN(1)
    at(970, 535); G.RUN(1)
    n = drawn("BuffFrameTooltip") + drawn("AuraButtonTooltip") + drawn("GameTooltip")
    k = fills("BuffFrameTooltip") + fills("GameTooltip")
    chk("off, in a fight: all three held (%d frames drawn, %d fills)" % (n, k), drawn("AuraButtonTooltip") == 0 and n <= (k if late else 0))
    G.FIGHT = False
    at(-100, -100); G.RUN(5)

    # --- tooltips ON again: alpha and hover back
    T.set(False)
    at(-100, -100); L.execute("RESET(); GAIN()"); G.RUN(1)
    chk("on again: the buff's tooltip draws again (%d frames)" % drawn("BuffFrameTooltip"), drawn("BuffFrameTooltip") >= 25)
    at(720, 270); L.execute("RESET(); ENTERS = 0"); G.RUN(1)
    chk("on again: the target's buff has its hover and tooltip back (%d frames)" % drawn("AuraButtonTooltip"), drawn("AuraButtonTooltip") >= 25)
    at(-100, -100); G.RUN(0.2)
    at(100, 440); L.execute("RESET()"); G.RUN(1)
    chk("on again: the pet frame's buff list draws again (%d frames)" % drawn("PartyMemberBuffTooltip"), drawn("PartyMemberBuffTooltip") >= 25)
    at(-100, -100); G.RUN(0.2)
    L.execute("BuffFrame.alpha = 1")
    at(940, 535); L.execute("RESET()"); G.RUN(1)
    chk("on again: the consolidation button's list draws again (%d frames)" % drawn("ConsolidatedBuffs.Tooltip"),
        drawn("ConsolidatedBuffs.Tooltip") >= 25)
    L.execute("BuffFrame.alpha = 0")
    at(-100, -100); G.RUN(5)
    st = T.stats() if T.stats else None
    chk("on again: no shield counted up (%s)" % (st.sh if st else "no stats"), st is not None and st.sh == 0)

    # --- what the add-on did
    errs = [G.ERRS[i] for i in range(1, len(G.ERRS) + 1)]
    chk("no forbidden object touched, no error (%s)" % (errs[:3] or "none"), not errs)
    allowed = {"SetAlpha", "GetAlpha", "IsShown", "IsVisible", "GetObjectType", "IsForbidden", "GetName", "HookScript", "GetRect",
               "GetEffectiveScale"}
    calls = dict(G.ADDON_CALLS.items())
    bad = sorted(k for k in calls if k not in allowed)
    chk("on the game's frames only reads, SetAlpha and HookScript (%s)" % (bad or sorted(calls)), not bad)
    clicky = L.eval("(function() local n = 0; for _, o in ipairs(ALL) do if not o.game and o.click then n = n + 1 end end; return n end)()")
    chk("no frame of ours takes clicks (%d)" % clicky, clicky == 0)
    chk("a plain game frame is never dimmed (Minimap alpha %s)" % G.Minimap.alpha, G.Minimap.alpha == 1)
    chk("no CVar written", G.CVAR_WRITES == 0)


def main():
    path = TRAIL
    if "--trail" in sys.argv:
        path = sys.argv[sys.argv.index("--trail") + 1]
    code = section(path)
    v = re.search(r'WC\.tips = \{ V = "([^"]+)"', code)
    print("Trail.lua: %s (tips %s)" % (path, v.group(1) if v else "?"))
    fails = []
    for mode in ("none", "deferred", "late"):
        for sealed in (False, True):
            scenario(code, mode, sealed, fails, "bufftips")
    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
