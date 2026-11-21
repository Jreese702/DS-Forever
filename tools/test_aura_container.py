"""GitHub #558 / #476 (the user's rulings 2026-11-19), off-device: the top screen's auras are the game's own aura containers (Trail.lua
WC.abox ab1 / WC.prd prd20, Hub.lua uf26 / hub71, the tooltip shield tt6), and the bottom screen's auras are hidden in a fight
(WowCompanionCharacter/Auras.lua a8).

    python tools/test_aura_container.py

Part 1 boots Trail.lua + Hub.lua in the render rig (tools/render_top_party.py) with a MOCKED AuraContainer: CreateFrame("AuraContainer",
..., "CustomAuraContainerTemplate") gives a frame whose methods record every call and check their arguments as the client's own source
does (Blizzard_CustomAuraContainer.lua: a valid filter string, a numeric max, a valid sort enum, an initializeFrame function), and whose
AddAuraGroup makes a batch of 10 buttons, calls initializeFrame on each, then LOCKS them (any later call raises, as
DenyTaintedAccessWhenAurasAreSecret does in a fight). The game's aura reads are counted and, in a fight, THROW as on the device
("Auras cannot be accessed when secret while tainted"). Checks: creation; the unit / filter / sort / layout setup; every button dressed
once in initializeFrame (icon, swipe, seconds, stacks, dispel border, pandemic region, tooltip hidden in combat); Edit Mode preview off;
SetUnit once; UpdateAllAuras on a target change; the in-combat path reads NO aura data and calls nothing but UpdateAllAuras; the old rows
never show beside the containers; the HUD bar's switch, the target bar's switch and no target hide the containers; Party mode moves the
HUD's container above the bars growing up; the tt6 shield lies over each container's host while tooltips are off, never otherwise; a
client without the container keeps the old rows (the probe says so).

Part 2 runs Auras.lua alone on a small stub: out of a fight its lists as before; from PLAYER_REGEN_DISABLED (or the game's own combat
word) a document with hide = true and empty lists and NO aura / tooltip / enchant read, its UNIT_AURA and heartbeat sends included;
PLAYER_REGEN_ENABLED brings the lists back at once.

Needs lupa. Exit code 1 on any failure."""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import render_top_party as rt  # noqa: E402
import test_compass as tc  # noqa: E402

ADDONS = os.path.join(rt.ROOT, "companion", "app", "src", "main", "assets", "companion", "addons")

MOCK = r'''
ACM = { made = {}, log = {}, reads = 0, throw = false, refuse = false, probes = {} }
CustomAuraContainerGroupDefaultOptions = {}
AuraContainerSortMethod = { Default = 0, BigDefensive = 1, UnitFrameDebuff = 2, ImportantOnly = 3, Expiration = 4, ExpirationOnly = 5 }
AuraContainerSortDirection = { Normal = 0, Reverse = 1 }
AnchorUtil = AnchorUtil or {}
AnchorUtil.FlowDirection = { Left = -1, Right = 1, Up = 1, Down = -1 }
Enum = Enum or {}
Enum.CustomAuraButtonDispelTypeTextureStyle = { Border = 0, BorderWithIcon = 1, Icon = 2, PreserveAsset = 3, CustomAsset = 4 }
-- AuraUtil.AuraFilters (this client's aurautil.lua), "!" negates
local TOK = { HELPFUL = true, HARMFUL = true, PLAYER = true, RAID = true, CANCELABLE = true, INCLUDE_NAME_PLATE_ONLY = true }
function ACM.validFilter(s)
    if type(s) ~= "string" or s == "" then return false end
    for part in s:gmatch("[^| ]+") do
        local p = part:gsub("^!", "")
        if p == "" or not TOK[p] then return false end
    end
    return true
end
local SORTS = { [0] = true, [1] = true, [2] = true, [3] = true, [4] = true, [5] = true }
local BTN = { "SetIcon", "SetDurationCooldown", "SetDurationText", "SetApplicationCount", "AddDispelTypeTexture", "AddPandemicRegion",
              "SetHideTooltipInCombat", "SetMouseClickEnabled" }
local function descendant(o, b)
    local p = type(o) == "table" and rawget(o, "parent") or nil
    for _ = 1, 4 do if rawequal(p, b) then return true end; p = type(p) == "table" and rawget(p, "parent") or nil end
    return false
end
local base = CreateFrame
do  -- the client's mouse split and strata read (the rig's regions take any other method as a no-op)
    local R = getmetatable(base("Frame"))
    rawset(R, "SetMouseMotionEnabled", function(self, on) self.motion = on end)
    rawset(R, "SetMouseClickEnabled", function(self, on) self.click = on end)
    rawset(R, "GetFrameStrata", function(self) return self.strata or "MEDIUM" end)
end
function CreateFrame(kind, name, parent, tmpl)
    if kind ~= "AuraContainer" then return base(kind, name, parent, tmpl) end
    if ACM.refuse then error("CreateFrame: Unknown frame type 'AuraContainer'") end
    assert(tmpl == "CustomAuraContainerTemplate", "the template: " .. tostring(tmpl))
    local c = base("Frame", name, parent)
    c.isAC, c.edit, c.groups, c.updates, c.unitSets = true, true, {}, 0, 0
    local function log(m) ACM.log[#ACM.log + 1] = { c = c, m = m, fight = GAME.fight == true } end
    function c:SetEditModePreviewEnabled(on) log("edit"); self.edit = on end
    function c:SetUnit(u) log("unit"); assert(type(u) == "string"); self.unit = u; self.unitSets = self.unitSets + 1 end
    function c:UpdateAllAuras() log("update"); self.updates = self.updates + 1 end
    function c:SetFlowLayoutAnchorPoint(p) log("anchor"); assert(type(p) == "string"); self.fanchor = p end
    function c:SetFlowLayoutGrowthDirection(h, v) log("grow"); assert(h == 1 or h == -1, "h"); assert(v == 1 or v == -1, "v"); self.fh, self.fv = h, v end
    function c:SetFlowLayoutMaximumLineSize(n) log("line"); assert(type(n) == "number"); self.fline = n end
    function c:AddAuraGroup(key, filter, opts)
        log("group")
        assert(type(key) == "string" and key ~= "", "groupKey must be a non-empty string.")
        assert(ACM.validFilter(filter), "Unknown aura filter component: " .. tostring(filter))
        assert(type(opts) == "table" and type(opts.maxFrameCount) == "number" and opts.maxFrameCount >= 0, "maxFrameCount")
        assert(SORTS[opts.sortMethod], "sortMethod must be a valid AuraContainerSortMethod.")
        assert(opts.sortDirection == 0 or opts.sortDirection == 1, "sortDirection")
        assert(type(opts.initializeFrame) == "function", "initializeFrame")
        local cand = opts.candidateFilters
        if cand ~= nil then
            assert(type(cand) == "table")
            if cand.maxDuration ~= nil then assert(type(cand.maxDuration) == "number" and cand.maxDuration >= 0, "maxDuration") end
        end
        local g = { key = key, filter = filter, opts = opts, buttons = {} }
        self.groups[#self.groups + 1] = g
        for i = 1, 10 do                                    -- one batch (CustomAuraContainerConstants.FrameCreationBatchSize)
            local b = base("Button", nil, self)
            b.isAB, b.wired, b.locked = true, {}, false
            for _, m in ipairs(BTN) do
                b[m] = function(self2, obj, o)
                    if self2.locked then error("locked: DenyTaintedAccessWhenAurasAreSecret") end
                    if m ~= "SetHideTooltipInCombat" and m ~= "SetMouseClickEnabled" then
                        assert(descendant(obj, self2), m .. ": bad object (must be a descendant of owner)")
                    end
                    self2.wired[m] = (o == nil) and (obj == true and true or "set") or o
                    if m == "SetHideTooltipInCombat" then self2.tipCombat = obj end
                end
            end
            opts.initializeFrame(b)
            b.locked = true
            g.buttons[i] = b
        end
    end
    ACM.made[#ACM.made + 1] = c
    return c
end
-- the game's aura reads: counted; in a fight they throw, as on the device
local CU = C_UnitAuras or {}
for _, k in ipairs({ "GetAuraDataByIndex", "GetAuraDataByAuraInstanceID", "GetUnitAuraInstanceIDs", "GetAuraDuration" }) do
    local o = CU[k]
    CU[k] = function(...)
        ACM.reads = ACM.reads + 1
        if ACM.throw then error("Auras cannot be accessed when secret while tainted") end
        if o then return o(...) end
    end
end
C_UnitAuras = CU
local ua = UnitAura
UnitAura = function(...) ACM.reads = ACM.reads + 1; if ACM.throw then error("Auras cannot be accessed when secret while tainted") end; if ua then return ua(...) end end
local send = WowCompanion.send
WowCompanion.send = function(j) if type(j) == "table" and j.probe == "aurabox" then ACM.probes[#ACM.probes + 1] = j end; if send then return send(j) end end
WowCompanion.encode = WowCompanion.encode or function(t) return t end
function ACM.byKey(k) local b = WowCompanion.abox.boxes[k]; return b and b.c or nil end
function ACM.vis(r) return r ~= nil and r:IsVisible() == true end
function ACM.anyOldShown(list) for _, o in ipairs(list or {}) do if o.f.shown then return true end end return false end
'''


def boot(refuse=False):
    rig = rt.Rig()
    rig.L.execute(MOCK)
    rig.G.ACM.refuse = refuse
    rig.scene(dict(n=1, pet=False, mode=False, pick=None, dead=None, oor=None, buffs=True))
    return rig


def lst(t):
    return [t[i] for i in range(1, len(t) + 1)] if t else []


def part1(chk):
    rig = boot()
    G, L, P, UF = rig.G, rig.L, rig.P, rig.UF
    A = G.WowCompanion.abox
    hud, tdeb, tbuf = G.ACM.byKey("hud"), G.ACM.byKey("tdeb"), G.ACM.byKey("tbuf")
    chk("creation: three containers from CustomAuraContainerTemplate (the HUD on player, the target bar's debuffs and buffs)",
        len(G.ACM.made) == 3 and hud is not None and tdeb is not None and tbuf is not None, len(G.ACM.made))
    if hud is None or tdeb is None or tbuf is None:
        return
    chk("units: SetUnit ONCE each, player / target / target", (hud.unit, tdeb.unit, tbuf.unit) == ("player", "target", "target")
        and hud.unitSets == 1 and tdeb.unitSets == 1 and tbuf.unitSets == 1, (hud.unit, tdeb.unit, tbuf.unit))
    chk("Edit Mode preview off on every container", hud.edit is False and tdeb.edit is False and tbuf.edit is False)
    hg = lst(hud.groups)
    chk("HUD: buffs (HELPFUL, at most BUFF_MAX, lasting BUFF_DUR or less) THEN debuffs (HARMFUL with a duration, at most DEBUFF_MAX)",
        [g.key for g in hg] == ["buffs", "debuffs"] and [g.filter for g in hg] == ["HELPFUL", "HARMFUL"]
        and hg[0].opts.maxFrameCount == P.BUFF_MAX and hg[1].opts.maxFrameCount == P.DEBUFF_MAX
        and hg[0].opts.candidateFilters.maxDuration == P.BUFF_DUR and hg[1].opts.candidateFilters.maxDuration == float("inf"),
        [(g.key, g.filter) for g in hg])
    chk("HUD: the flow from the bar's left edge, growing right then down, wrapping at the bar's width",
        hud.fanchor == "TOPLEFT" and hud.fh == 1 and hud.fv == -1 and abs(hud.fline - A.boxes.hud.host.w) < 1e-6,
        (hud.fanchor, hud.fh, hud.fv, hud.fline))
    dg = lst(tdeb.groups)
    chk("target: yours first (HARMFUL|PLAYER), then the others (HARMFUL|!PLAYER), both sorted by expiration",
        [g.filter for g in dg] == ["HARMFUL|PLAYER", "HARMFUL|!PLAYER"] and all(g.opts.sortMethod == 4 for g in dg)
        and all(g.opts.sortDirection == 0 for g in dg), [(g.filter, g.opts.sortMethod) for g in dg])
    bg = lst(tbuf.groups)
    chk("target: the buffs from the bar's right edge growing left (the old row's place)",
        [g.filter for g in bg] == ["HELPFUL"] and tbuf.fanchor == "TOPRIGHT" and tbuf.fh == -1, (tbuf.fanchor, tbuf.fh))
    # every button dressed once, in initializeFrame, before the lock
    def wired(c, need):
        bad = []
        for g in lst(c.groups):
            for b in lst(g.buttons):
                for m in need:
                    if not b.wired[m]:
                        bad.append(m)
                if b.tipCombat is not True:
                    bad.append("tipCombat")
        return bad
    base_need = ["SetIcon", "SetDurationCooldown", "SetApplicationCount", "AddDispelTypeTexture", "AddPandemicRegion"]
    bh = wired(hud, base_need + ["SetDurationText"])
    chk("HUD buttons: icon, swipe, seconds (text binding), stacks, dispel border, pandemic region, tooltip hidden in combat (%d buttons)"
        % sum(len(lst(g.buttons)) for g in hg), not bh, sorted(set(bh)))
    bt = wired(tdeb, base_need) + wired(tbuf, base_need)
    chk("target buttons: icon, swipe (the game's seconds in its corner), stacks, dispel border, pandemic region, tooltip hidden in combat",
        not bt, sorted(set(bt)))
    o = dg[0].buttons[1].wired.AddDispelTypeTexture
    chk("the dispel border: the game's Border style, on harmful auras, with or without a dispel type",
        o.style == 0 and o.showWhenHarmful is True and o.showWhenHelpful is False and o.showWithoutDispelType is True, dict(o))
    o = bg[0].buttons[1].wired.AddDispelTypeTexture
    chk("the buffs' border: never on a helpful aura", o.showWhenHelpful is False and o.showWithoutDispelType is False, dict(o))
    nt = hg[0].buttons[1].wired.SetDurationText
    chk("the HUD's seconds: the game's binding with the white / gold colour curve where the client has one, else its defaults",
        nt == "set" or (hasattr(nt, "textColor")), nt)
    chk("no target button had SetDurationText (the Cooldown's own numbers)", all(not b.wired.SetDurationText for g in dg + bg for b in lst(g.buttons)))
    # in place of the old rows
    rig.run(1.0)
    chk("the old rows never show beside the containers (HUD / target)", not G.ACM.anyOldShown(P.buffs) and not G.ACM.anyOldShown(P.debuffs)
        and not G.ACM.anyOldShown(UF.tb.f.deb) and not G.ACM.anyOldShown(UF.tb.f.buf))
    hh, dh, bh2 = A.boxes.hud.host, A.boxes.tdeb.host, A.boxes.tbuf.host
    chk("the hosts shown with their bars (HUD shown in the fight, the target bar up)", G.ACM.vis(hh) and G.ACM.vis(dh) and G.ACM.vis(bh2),
        (G.ACM.vis(hh), G.ACM.vis(dh), G.ACM.vis(bh2)))
    chk("the HUD host under the bars (the old row's place), the target's at the bar's aura row",
        hh.point[1] == "TOPLEFT" and hh.point[3] == "TOPLEFT" and hh.point[5] < 0 and dh.point[1] == "TOPLEFT" and bh2.point[1] == "TOPRIGHT",
        (lst(hh.point), lst(dh.point), lst(bh2.point)))
    # THE FIGHT: no aura read at all, only UpdateAllAuras on a target change
    G.ACM.reads = 0
    G.ACM.throw = True
    G.GAME.fight = True
    G.ME.fight = True
    n0 = len(G.ACM.log)
    u0 = (hud.updates, tdeb.updates, tbuf.updates)
    rig.fire("PLAYER_REGEN_DISABLED")
    for _ in range(3):
        rig.fire("UNIT_AURA", "player")
        rig.fire("UNIT_AURA", "target")
        rig.run(0.5)
    rig.fire("PLAYER_TARGET_CHANGED")
    rig.run(1.0)
    chk("in a fight: NO aura read by Trail or Hub (%d)" % G.ACM.reads, G.ACM.reads == 0, G.ACM.reads)
    calls = [G.ACM.log[i].m for i in range(n0 + 1, len(G.ACM.log) + 1)]
    chk("in a fight: the containers get nothing but UpdateAllAuras (%s)" % calls, set(calls) <= {"update"}, calls)
    chk("a target change: UpdateAllAuras once on each target container, never on the HUD's",
        (hud.updates, tdeb.updates - u0[1], tbuf.updates - u0[2]) == (u0[0], 1, 1), (hud.updates, tdeb.updates, tbuf.updates, u0))
    chk("in a fight: the hosts still shown, the old rows still hidden", G.ACM.vis(hh) and G.ACM.vis(dh)
        and not G.ACM.anyOldShown(P.buffs) and not G.ACM.anyOldShown(UF.tb.f.deb))
    r = L.eval('(function() local ok, e = pcall(function() WowCompanion.prd.drawAuras(true) end); return ok and "ok" or tostring(e) end)()')
    chk("in a fight: a direct drawAuras raises nothing and reads nothing", r == "ok" and G.ACM.reads == 0, (r, G.ACM.reads))
    # THE SWITCHES: the HUD bar's (npWant.prd) and the target bar's (UF.target) hide the containers with their bars
    G.WowCompanion.npWant.prd = False
    rig.run(0.5)
    chk("HUD switch off: the HUD's container hidden", not G.ACM.vis(hh))
    G.WowCompanion.npWant.prd = True
    rig.run(1.0)
    chk("HUD switch on: shown again", G.ACM.vis(hh))
    L.eval('WowCompanion.uf.set("target", false)')
    rig.run(0.3)
    chk("target bar switch off: both target containers hidden", not G.ACM.vis(dh) and not G.ACM.vis(bh2))
    L.eval('WowCompanion.uf.set("target", true)')
    rig.run(0.3)
    chk("target bar switch on: shown again", G.ACM.vis(dh) and G.ACM.vis(bh2))
    tgt = G.UNITS.target
    G.UNITS.target = None
    rig.fire("PLAYER_TARGET_CHANGED")
    rig.run(0.5)
    chk("no target: the target containers hidden with the bar", not G.ACM.vis(dh) and not G.ACM.vis(bh2))
    G.UNITS.target = tgt
    rig.fire("PLAYER_TARGET_CHANGED")
    rig.run(1.0)
    chk("a target again: shown, and refreshed (UpdateAllAuras)", G.ACM.vis(dh) and tdeb.updates >= u0[1] + 3, tdeb.updates)
    chk("still no aura read in the fight (%d)" % G.ACM.reads, G.ACM.reads == 0, G.ACM.reads)
    # PARTY MODE: the HUD's auras above the bars, growing up (prd15's move, the flow's anchor and growth only)
    G.PM.on = True
    rig.run(1.5)
    chk("Party mode: the HUD's container above the bars, growing up (%s, %s / %s)" % (lst(hh.point), hud.fanchor, hud.fv),
        P.auraUp is True and hh.point[1] == "BOTTOMLEFT" and hh.point[5] > 0 and hud.fanchor == "BOTTOMLEFT" and hud.fv == 1)
    G.PM.on = False
    rig.run(1.5)
    chk("Party mode off: back under the bars, growing down", P.auraUp is False and hh.point[1] == "TOPLEFT" and hud.fanchor == "TOPLEFT" and hud.fv == -1)
    chk("the moves read no aura either (%d)" % G.ACM.reads, G.ACM.reads == 0)
    # OUT OF THE FIGHT: still the containers, still no read (the look never changes)
    G.ACM.throw = False
    G.GAME.fight = False
    G.ME.fight = False
    rig.fire("PLAYER_REGEN_ENABLED")
    rig.fire("UNIT_AURA", "player")
    rig.run(1.0)
    chk("out of the fight: the same containers, still no aura read (%d)" % G.ACM.reads, G.ACM.reads == 0 and len(G.ACM.made) == 3)
    # THE TOOLTIP SHIELD (tt6): over each container's host while tooltips are off, out of a fight and in one
    T = G.WowCompanion.tips
    L.execute('WowCompanionDB = WowCompanionDB or {}')
    T.set(True)
    rig.run(0.3)
    T.shieldTick()
    sh = {k: T.boxShields[k] for k in ("hud", "tdeb", "tbuf")}
    up = {k: (e is not None and e.up is True and e.mode == "box") for k, e in sh.items()}
    chk("tt6: tooltips off: a shield over each container's host (%s)" % up, all(up.values()), up)
    e = sh["hud"]
    chk("tt6: the shield is motion only, anchored to OUR host, above its buttons",
        L.eval('(function() local T, h = WowCompanion.tips, WowCompanion.abox.boxes.hud.host; local e = T.boxShields.hud; '
               'return e ~= nil and rawequal(e.c, h) and e.f.motion == true and e.f.click == false '
               'and e.f.level == h:GetFrameLevel() + T.BOX_LIFT and e.f.strata == h:GetFrameStrata() end)()') is True,
        e and (e.f.level, e.f.strata, e.f.motion, e.f.click))
    G.WowCompanion.npWant.prd = False
    rig.run(0.5)
    T.shieldTick()
    chk("tt6: the HUD's host hidden: its shield down", sh["hud"].up is not True)
    G.WowCompanion.npWant.prd = True
    rig.run(1.0)
    T.set(False)
    T.shieldTick()
    chk("tt6: tooltips on: every container shield down", all(T.boxShields[k] is None or T.boxShields[k].up is not True for k in sh))
    pr = lst(G.ACM.probes)
    chk("probe aurabox: one line per piece, made, no step refused (%s)" % [(p.key, p.made) for p in pr],
        sorted(p.key for p in pr) == ["hud", "tbuf", "tdeb"] and all(p.made is True for p in pr)
        and all(len(list(p.steps.keys())) == 0 for p in pr), [(p.key, p.made, dict(p.steps)) for p in pr])
    w = A.word()
    chk("the hub document's word (abox): every piece ok", w.v == "ab1" and w.b.hud == "ok" and w.b.tdeb == "ok" and w.b.tbuf == "ok", dict(w.b))
    # TAGS
    hub = open(os.path.join(ADDONS, "WowCompanionHub", "Hub.lua"), encoding="utf-8").read()
    chk("tags: Trail prd21 / ab1 / tt6, Hub hub74 / uf26", P.V == "prd21" and A.V == "ab1" and T.V == "tt6" and UF.V == "uf26"
        and re.search(r'local HV = "hub74"', hub) is not None, (P.V, A.V, T.V, UF.V))

    # A CLIENT WITHOUT THE CONTAINER (CreateFrame refuses the type): the old rows, as before, and the probe says so
    old = boot(refuse=True)
    Go = old.G
    old.run(1.0)
    chk("no container on the client: the old rows show (HUD %s, target %s)" % (Go.ACM.anyOldShown(old.P.buffs), Go.ACM.anyOldShown(old.UF.tb.f.deb)),
        len(Go.ACM.made) == 0 and Go.ACM.anyOldShown(old.P.buffs) and Go.ACM.anyOldShown(old.UF.tb.f.deb))
    pr = lst(Go.ACM.probes)
    chk("no container: the probe says refused, with the client's error", pr and all(p.made is False for p in pr)
        and any("Unknown frame type" in str(p.steps.create) for p in pr), [(p.key, p.made, dict(p.steps)) for p in pr])


# ---------------------------------------------------------------------------------------------------------- part 2: Auras.lua
STUB2 = r'''
FIGHT, LOCK = false, false
READS = { aura = 0, tip = 0, ench = 0 }
SENT = {}
local T = 1000
function GetTime() return T end
function ADV(s) T = T + s end
local function ser(v)
    if type(v) ~= "table" then return tostring(v) end
    local keys = {}
    for k in pairs(v) do keys[#keys + 1] = tostring(k) end
    table.sort(keys)
    local out = {}
    for _, k in ipairs(keys) do local x = v[k]; if x == nil then x = v[tonumber(k)] end; out[#out + 1] = k .. "=" .. ser(x) end
    return "{" .. table.concat(out, ",") .. "}"
end
DOCS = {}
WowCompanion = {
    S = function() end, SECRET = "\1",
    CALL = function(f, ...) if type(f) ~= "function" then return false end return pcall(f, ...) end,
    isPlain = function() return true end,
    encode = function(t) DOCS[ser(t)] = t; LASTDOC = t; return ser(t) end,
    send = function(s) SENT[#SENT + 1] = DOCS[s] end,
    push = function() end,
    snapshot = function() end,
}
function hooksecurefunc(t, k, fn) local o = t[k]; t[k] = function(...) local r = o(...); fn(...); return r end end
function InCombatLockdown() return FIGHT == "lock" end
function UnitAffectingCombat() return FIGHT == "uac" end
FRAMES2 = {}
function CreateFrame()
    local f = { ev = {} }
    function f:RegisterEvent(e) self.ev[e] = true end
    function f:RegisterUnitEvent(e) self.ev[e] = true end
    function f:SetScript(k, fn) self[k] = fn end
    FRAMES2[#FRAMES2 + 1] = f
    return f
end
TIMERS = {}
C_Timer = { After = function(d, fn) TIMERS[#TIMERS + 1] = fn end }
function FLUSH() local q = TIMERS; TIMERS = {}; for _, fn in ipairs(q) do fn() end end
local B = { { auraInstanceID = 11, name = "Lightning Shield", icon = 136051, duration = 600, expirationTime = 1500, applications = 3 },
            { auraInstanceID = 12, name = "Water Walking", icon = 135863, duration = 600, expirationTime = 1550 } }
local D = { { auraInstanceID = 21, name = "Rend", icon = 132155, duration = 15, expirationTime = 1010, dispelName = "Bleed" } }
local function L(f) return f == "HELPFUL" and B or D end
C_UnitAuras = {
    GetAuraDataByIndex = function(u, i, f) READS.aura = READS.aura + 1; return L(f)[i] end,
    GetUnitAuraInstanceIDs = function(u, f) READS.aura = READS.aura + 1; local o = {}; for _, a in ipairs(L(f)) do o[#o + 1] = a.auraInstanceID end; return o end,
    GetAuraDataByAuraInstanceID = function(u, id) READS.aura = READS.aura + 1; for _, a in ipairs(B) do if a.auraInstanceID == id then return a end end; for _, a in ipairs(D) do if a.auraInstanceID == id then return a end end end,
}
C_TooltipInfo = { GetUnitBuffByAuraInstanceID = function() READS.tip = READS.tip + 1; return { lines = { { leftText = "x" }, { leftText = "Absorbs damage." } } } end,
                  GetUnitDebuffByAuraInstanceID = function() READS.tip = READS.tip + 1; return { lines = {} } end }
Enum = { WeaponSlot = { MainHand = 0 } }
C_Item = { GetWeaponEnchantInfo = function() READS.ench = READS.ench + 1; return {} end }
function GetInventoryItemTexture() return 1 end
function EV(e, u) for _, f in ipairs(FRAMES2) do if f.ev[e] and f.OnEvent then f.OnEvent(f, e, u) end end end
function NREADS() return READS.aura + READS.tip + READS.ench end
'''


def part2(chk):
    L = tc.lupa.LuaRuntime()
    L.execute(STUB2)
    G = L.globals()
    src = open(os.path.join(ADDONS, "WowCompanionCharacter", "Auras.lua"), encoding="utf-8").read()
    L.execute(src)
    W = G.WowCompanion.auras
    chk("Auras.lua a8", W.version == "a8", W.version)
    W.emit(True)
    d = G.LASTDOC
    chk("out of a fight: the lists as before (2 buffs, 1 debuff), read from the game", len(lst(d.b)) == 2 and len(lst(d.d)) == 1
        and d.hide is None and G.NREADS() > 0, (len(lst(d.b)), len(lst(d.d)), d.hide))
    r0 = G.NREADS()
    n0 = len(lst(G.SENT))
    G.FIGHT = "event"
    G.EV("PLAYER_REGEN_DISABLED")
    d = G.SENT[len(G.SENT)]
    chk("the fight's start (PLAYER_REGEN_DISABLED): a document at once, hide = true, every list empty",
        len(lst(G.SENT)) == n0 + 1 and d.hide is True and d.combat is True and len(lst(d.b)) == 0 and len(lst(d.d)) == 0
        and len(lst(d.e)) == 0, dict(d))
    G.EV("UNIT_AURA", "player")
    G.ADV(1)
    G.FLUSH()
    G.EV("UNIT_AURA", "player")
    G.FLUSH()
    G.WowCompanion.snapshot("heartbeat")
    dh = G.SENT[len(G.SENT)]
    chk("in the fight: UNIT_AURA and the heartbeat read nothing (aura / tooltip / enchant reads %d)" % (G.NREADS() - r0),
        G.NREADS() == r0, dict(G.READS))
    chk("in the fight: the heartbeat's full document hides them too", dh.hide is True and dh.full is True and len(lst(dh.b)) == 0)
    G.FIGHT = False
    G.EV("PLAYER_REGEN_ENABLED")
    d = G.SENT[len(G.SENT)]
    chk("the fight's end (PLAYER_REGEN_ENABLED): the lists back at once", d.hide is None and len(lst(d.b)) == 2 and len(lst(d.d)) == 1)
    # the game's own combat word without the event (a missed REGEN_DISABLED): hidden all the same
    for word in ("uac", "lock"):
        G.FIGHT = word
        r1 = G.NREADS()
        W.emit(True)
        d = G.LASTDOC
        chk("the game's combat word alone (%s): hidden, nothing read" % word, d.hide is True and G.NREADS() == r1)
    G.FIGHT = False
    W.emit(True)
    chk("out of it again: shown", G.LASTDOC.hide is None and len(lst(G.LASTDOC.b)) == 2)
    chk("a pick in a fight is refused (removal is out of combat only)", "if InCombatLockdown() then return ack(\"pick\", key, false, \"in combat\") end" in src)


def main():
    fails = []

    def chk(name, ok, got=''):
        print(("PASS " if ok else "FAIL ") + name + ((" -> " + str(got)) if got != '' and not ok else ''))
        if not ok:
            fails.append(name)

    part1(chk)
    part2(chk)
    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
