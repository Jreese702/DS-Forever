"""THE TOP SCREEN'S PARTY BLOCK, RENDERED off-device (the user, 2026-11-12: "The renders for party that I wanted to see were for the top
screen. I had someone join my group on an old build and their health bar was a mile long").

    python tools/render_top_party.py [--out DIR] [--rev GIT_REV --tag NAME] [--shapes party5,...]

Boots Trail.lua (the HUD's bars: WC.prd, the pet strip, the pick boxes) and Hub.lua (WC.uf: the party block, its Select tab, the
target bar) in one lupa runtime on the compass tests' stub game, the stub recording every frame / texture / font string's points,
size, colour, alpha, atlas / texture and text. Each region's final rect is resolved from its anchors (as the client does: two
opposite points set the size, else the region's own), then a 1920 x 1080 PNG is drawn: a dark game-like ground, textures as their
colour / atlas-name placeholder boxes in their tint, the HUD art's rim pieces as the rim, text as text. Shapes: a party of 2 .. 5
(you + 1 .. 4 members), each without and with a pet, Party mode off and on (the gold ring on a member; extra shapes with the pick on
you / your pet: the HUD bars' / pet strip's gold box), one member dead and one out of range in the bigger groups, a hostile target.

--width-options renders a full row's bar at three widths side by side (W_BAR 194 today, 1427's 228, 300) for the user's pick:
top_width_options.png (hub41 review, 2026-11-12: the rows' widths are the user's call, not decided here).

--rev renders that commit's Hub.lua instead (git show REV:...), e.g. --rev 097503d8 --tag 1428 --shapes party5 for the old 1428.
The geometry half (boot / scene / rects) is shared with tools/test_party_top.py (the width checks); PIL only for the PNGs."""
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import test_compass as tc  # noqa: E402
import test_prd as tp  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HUB_REL = "companion/app/src/main/assets/companion/addons/WowCompanionHub/Hub.lua"
HUB = os.path.join(ROOT, *HUB_REL.split("/"))
OUT = "C:/Users/Jrees/AppData/Local/Temp/claude/6c5148a90427b4bc/789b5138-341f-4ef8-8102-099551ca3a8a/scratchpad/top-party"

# before Trail: the top screen at 1920 x 1080 UI units (u(px) = px), every atlas the client would know
PRE = r'''
UIParent.hh, UIParent.ww = 1080, 1920
GAME.physH, GAME.uiH = 1080, 1080
ATLAS_FILE = {}
C_Texture = { GetAtlasInfo = function(a)
    if type(a) ~= "string" then return nil end
    local w, h = 132, 19
    if a:find("sidetab") then w, h = 110, 120 end
    ATLAS_FILE[a] = ATLAS_FILE[a] or ("atlas:" .. a)
    return { file = ATLAS_FILE[a], leftTexCoord = 0, rightTexCoord = 1, topTexCoord = 0, bottomTexCoord = 1, width = w, height = h }
end }
'''

# after test_prd's GAMEPLUS: the geometry recorder, every region listed, unknown methods as no-ops (Hub calls more than the compass)
GEO = r'''
local R = getmetatable(CreateFrame("Frame"))
ALLR = {}
local SEQ = 0
local function note(o) SEQ = SEQ + 1; rawset(o, "seq", SEQ); ALLR[#ALLR + 1] = o; return o end
local cf, ct, cfs = CreateFrame, R.CreateTexture, R.CreateFontString
function CreateFrame(kind, name, parent, tmpl)
    local f = cf(kind, name, parent, tmpl)
    if f.parent == nil and kind ~= "Texture" then f.parent = UIParent end
    if name then _G[name] = f end
    return note(f)
end
function R:CreateTexture(...) return note(ct(self, ...)) end
function R:CreateFontString(...) return note(cfs(self, ...)) end
local PN = { TOPLEFT = true, TOP = true, TOPRIGHT = true, LEFT = true, CENTER = true, RIGHT = true, BOTTOMLEFT = true, BOTTOM = true, BOTTOMRIGHT = true }
function R:SetPoint(p, a, b, c, d)
    local rel, rp, x, y
    if a == nil or type(a) == "number" then rel, rp, x, y = nil, p, a or 0, b or 0
    else
        rel = a
        if type(b) == "string" then rp, x, y = b, c or 0, d or 0 else rp, x, y = p, b or 0, c or 0 end
    end
    if type(rel) == "string" then rel = _G[rel] end
    assert(PN[p] and PN[rp], "a bad point " .. tostring(p) .. " / " .. tostring(rp))
    local l = rawget(self, "anch") or {}
    for i = #l, 1, -1 do if l[i][1] == p then table.remove(l, i) end end
    l[#l + 1] = { p, rel, rp, x, y }
    self.anch = l
    self.point = { p, rel, rp, x, y }
end
function R:ClearAllPoints() self.anch = nil; self.point = nil end
function R:SetAllPoints(t)
    t = t or self.parent
    self:SetPoint("TOPLEFT", t, "TOPLEFT", 0, 0); self:SetPoint("BOTTOMRIGHT", t, "BOTTOMRIGHT", 0, 0)
end
function R:SetSize(w, h) self.w, self.h = w, h end
function R:SetScale(k) self.scale = k end
-- uf18: a frame's scale relative to UIParent (its own SetScale times its parents'): its size and its offsets are in those units
function ESCALE(r)
    local s, o = 1, r
    while o and not rawequal(o, UIParent) do local k = tonumber(rawget(o, "scale")); if k then s = s * k end; o = o.parent end
    return s
end
function R:SetWidth(w) self.w = w end
function R:SetHeight(h) self.h = h end
local sbt = R.SetStatusBarTexture
function R:SetStatusBarTexture(t)
    if type(t) == "string" then local x = self:CreateTexture(nil, "ARTWORK"); x:SetTexture(t); t = x end
    sbt(self, t); if type(t) == "table" then t.sbOwner = self end
end
function R:SetJustifyH(j) self.jh = j end
function R:GetParent() return self.parent end
function R:SetGradient() self.grad = true end
function R:SetFormattedText(fmt, ...) self.text = string.format(fmt, ...) end
function R:IsVisible()
    local o = self
    while o do if o.shown == false then return false end; o = o.parent end
    return true
end
function R:GetObjectType() return self.kind == "fs" and "FontString" or (self.kind == "tex" and "Texture" or self.kind) end
local function noop() end
setmetatable(R, { __index = function(_, k) if type(k) == "string" and k:match("^[A-Z]") then return noop end end })

-- THE RESOLVER: a region's rect { l, b, r, t } in UIParent units (y up), from its anchors as the client places them
local PT = { TOPLEFT = { 0, 1 }, TOP = { 0.5, 1 }, TOPRIGHT = { 1, 1 }, LEFT = { 0, 0.5 }, CENTER = { 0.5, 0.5 }, RIGHT = { 1, 0.5 },
             BOTTOMLEFT = { 0, 0 }, BOTTOM = { 0.5, 0 }, BOTTOMRIGHT = { 1, 0 } }
local cache = {}
local function plain(v) if type(v) == "table" then return tonumber(rawget(v, "n")) end return tonumber(v) end
RPLAIN = plain
function RECT_RESET() cache = {} end
local function natural(r)
    local w, h = tonumber(rawget(r, "w")), tonumber(rawget(r, "h"))
    if r.kind == "fs" then
        local s = tonumber(rawget(r, "fsize")) or 12
        local tx = rawget(r, "text"); tx = (type(tx) == "string" or type(tx) == "number") and tostring(tx) or ""
        if not w or w == 0 then w = #tx * s * 0.56 end
        if not h or h == 0 then h = s * 1.15 end
    end
    return w or 0, h or 0
end
function RECT(r)
    if r == nil or rawequal(r, UIParent) then return { 0, 0, 1920, 1080 } end
    if cache[r] then return cache[r] end
    cache[r] = { 0, 0, 0, 0 }
    local own = rawget(r, "sbOwner")
    if own then
        local o = RECT(own)
        local mn, mx, v = tonumber(rawget(own, "min")) or 0, tonumber(rawget(own, "max")), plain(rawget(own, "value"))
        local f = 1
        if mx and v and mx > mn then f = math.max(0, math.min(1, (v - mn) / (mx - mn))) end
        cache[r] = { o[1], o[2], o[1] + (o[3] - o[1]) * f, o[4] }
        return cache[r]
    end
    local w, h = natural(r)
    local es = ESCALE(r)
    w, h = w * es, h * es
    local an = rawget(r, "anch")
    if not an or #an == 0 then
        if r.kind == "tex" or r.kind == "fs" then cache[r] = { 0, 0, 0, 0 }; r.unplaced = true; return cache[r] end
        local p = RECT(r.parent)
        cache[r] = { p[1], p[4] - h, p[1] + w, p[4] }
        return cache[r]
    end
    local hx, vy = {}, {}
    for _, a in ipairs(an) do
        local rr = RECT(a[2] or r.parent)
        local q, s = PT[a[3]], PT[a[1]]
        local px = rr[1] + (rr[3] - rr[1]) * q[1] + (plain(a[4]) or 0) * es
        local py = rr[2] + (rr[4] - rr[2]) * q[2] + (plain(a[5]) or 0) * es
        hx[#hx + 1] = { s[1], px }; vy[#vy + 1] = { s[2], py }
    end
    local function solve(cs, size)
        local lo, hi = cs[1], cs[1]
        for _, c in ipairs(cs) do if c[1] < lo[1] then lo = c end; if c[1] > hi[1] then hi = c end end
        if hi[1] > lo[1] then
            local span = (hi[2] - lo[2]) / (hi[1] - lo[1])
            return lo[2] - lo[1] * span, lo[2] - lo[1] * span + span
        end
        return lo[2] - lo[1] * size, lo[2] - lo[1] * size + size
    end
    local l, rr = solve(hx, w)
    local b, t = solve(vy, h)
    cache[r] = { l, b, rr, t }
    return cache[r]
end
local LAYER = { BACKGROUND = 0, BORDER = 1, ARTWORK = 2, OVERLAY = 3, HIGHLIGHT = 4 }
local STRATA = { BACKGROUND = 0, LOW = 1, MEDIUM = 2, HIGH = 3, DIALOG = 4, FULLSCREEN = 5, TOOLTIP = 7 }
local function frameOf(r) if r.kind == "tex" or r.kind == "fs" then return r.parent end return r end
local function level(f)
    if f == nil or rawequal(f, UIParent) then return 0 end
    local l = tonumber(rawget(f, "level"))
    if l then return l end
    return level(f.parent) + 1
end
local function strata(f)
    while f and not rawequal(f, UIParent) do local s = rawget(f, "strata"); if s then return STRATA[s] or 2 end; f = f.parent end
    return 2
end
-- every drawn region under one of the roots: visible, its alpha, its rect, what it shows
function COLLECT(roots)
    RECT_RESET()
    local out = {}
    for _, r in ipairs(ALLR) do
        if r.kind == "tex" or r.kind == "fs" then
            local o, root, vis, alpha = r, nil, true, 1
            while o and not rawequal(o, UIParent) do
                if o.shown == false then vis = false end
                local a = plain(rawget(o, "alpha")); if a then alpha = alpha * a end
                if roots[o] then root = roots[o] end
                o = o.parent
            end
            if root and vis and alpha > 0.001 then
                local q = RECT(r)
                if not r.unplaced and (q[3] - q[1]) > 0.01 and (q[4] - q[2]) > 0.01 then
                    local f = frameOf(r)
                    local own = rawget(r, "sbOwner")
                    out[#out + 1] = { kind = r.kind, root = root, l = q[1], b = q[2], r = q[3], t = q[4], alpha = alpha,
                        ct = rawget(r, "colortex"), col = rawget(r, "col"), tcol = rawget(r, "tcolor"), atlas = rawget(r, "atlas"),
                        tex = rawget(r, "tex"), text = rawget(r, "text"), fsize = (tonumber(rawget(r, "fsize")) or 12) * ESCALE(r), jh = rawget(r, "jh"),
                        sbc = own and rawget(own, "color") or nil, layer = r.layer, grad = rawget(r, "grad"),
                        key = strata(f) * 1e9 + level(f) * 1e6 + (LAYER[r.layer or "ARTWORK"] or 2) * 1e4 + ((tonumber(r.sub) or 0) + 8) * 1e2,
                        seq = r.seq }
                end
            end
        end
    end
    table.sort(out, function(a, b) if a.key ~= b.key then return a.key < b.key end return a.seq < b.seq end)
    return out
end
'''

# the party, PadMap's exports, the target: what each shape sets
GAME2 = r'''
local base = { UnitHealth = UnitHealth, UnitHealthMax = UnitHealthMax, UnitPower = UnitPower, UnitPowerMax = UnitPowerMax,
               UnitPowerType = UnitPowerType, UnitName = UnitName, UnitIsDeadOrGhost = UnitIsDeadOrGhost, UnitExists = UnitExists }
local function secret(n) return setmetatable({ n = n }, { secret = true }) end
UNITS = {}
local function X(u) return UNITS[u] end
function UnitHealth(u) local x = X(u); if x then return secret(x.dead and 0 or x.hp) end return base.UnitHealth(u) end
function UnitHealthMax(u) local x = X(u); if x then return x.hpMax end return base.UnitHealthMax(u) end
function UnitPower(u, t) local x = X(u); if x then return secret(x.pw) end return base.UnitPower(u, t) end
function UnitPowerMax(u, t) local x = X(u); if x then return x.pwMax end return base.UnitPowerMax(u, t) end
function UnitPowerType(u) local x = X(u); if x then return x.ptype or 0, x.tok or "MANA" end return base.UnitPowerType(u) end
function UnitName(u) local x = X(u); if x then return x.name end if u:match("^party") or u:match("target") then return nil end return base.UnitName(u) end
function UnitIsDeadOrGhost(u) local x = X(u); if x then return x.dead == true end return base.UnitIsDeadOrGhost(u) end
function UnitExists(u) if X(u) then return true end if u:match("^party") or u:match("target") then return false end return base.UnitExists(u) end
PLAYERCLS = "SHAMAN"
function UnitClass(u) local x = X(u); if x then return x.cls, x.cls end if u == "player" then return PLAYERCLS, PLAYERCLS end end
function UnitLevel(u) local x = X(u); if x then return x.lv end return 6 end
function UnitIsConnected(u) local x = X(u); if x then return not x.off end return true end
function UnitIsUnit(a, b) return b == "target" and a == TARGET end
function UnitCanAttack(a, b) local x = X(b); return x ~= nil and x.hostile == true end
function UnitReaction(u) local x = X(u); return (x and x.hostile) and 2 or 5 end
function UnitClassification() return "normal" end
function UnitSelectionColor(u) local x = X(u); if x and x.hostile then return 0.9, 0.12, 0.1 end return 0, 1, 0 end
function GetRaidTargetIndex() return nil end
function IsInRaid() return false end
function GetNumSubgroupMembers() local n = 0; for k in pairs(UNITS) do if k:match("^party%d$") then n = n + 1 end end; return n end
function GetNumGroupMembers() return GetNumSubgroupMembers() + 1 end
function IsInGroup() return GetNumSubgroupMembers() > 0 end
RAID_CLASS_COLORS = { SHAMAN = { r = 0, g = 0.44, b = 0.87 }, WARRIOR = { r = 0.78, g = 0.61, b = 0.43 }, MAGE = { r = 0.25, g = 0.78, b = 0.92 },
                      PRIEST = { r = 1, g = 1, b = 1 }, ROGUE = { r = 1, g = 0.96, b = 0.41 }, HUNTER = { r = 0.67, g = 0.83, b = 0.45 } }
C_ClassColor = nil
PM = { on = false, u = nil, sel = "bound", rng = {}, units = {} }
TARGET = nil
'''

PADMAP = r'''
WowCompanion.padMap = { party = {
    on = function() return PM.on end,
    box = function() return { u = PM.u } end,
    lb = function() return { self = "PAD1", pet = "PAD3", p1 = "PADDUP", p2 = "PADDDOWN", p3 = "PADDLEFT", p4 = "PADDRIGHT" } end,
    rng = function(u) return PM.rng[u] end,
    units = function() return PM.units end,
    selWant = function() return PM.sel end,
} }
WowCompanion.partyOk = true
'''

HUBSTUB = r'''
WowCompanion.S = function(fn) local ok, v = pcall(fn); if ok then return v end end
WowCompanion.push = function() end
WowCompanion.snapshot = function() end
WowCompanion.pad = { op = function(name, ns, fn) return 1 end, describe = function() return {} end }
WowCompanion.isPlain = function(v) return not (type(v) == "table" and getmetatable(v) and getmetatable(v).secret == true) end
SlashCmdList = SlashCmdList or {}
for _, n in ipairs({ "PlayerFrame", "PetFrame", "TargetFrame", "TargetFrameToT", "ComboFrame" }) do _G[n] = _G[n] or CreateFrame("Frame", n) end
function GetCurrentKeyBoardFocus() return nil end
'''

MEMBERS = [
    {"name": "Brakka", "cls": "WARRIOR", "lv": 7, "hp": 820, "hpMax": 1000, "pw": 30, "pwMax": 100, "ptype": 1, "tok": "RAGE"},
    {"name": "Elowen", "cls": "PRIEST", "lv": 6, "hp": 540, "hpMax": 800, "pw": 70, "pwMax": 100},
    {"name": "Quill", "cls": "MAGE", "lv": 6, "hp": 300, "hpMax": 700, "pw": 90, "pwMax": 100},
    {"name": "Sly", "cls": "ROGUE", "lv": 5, "hp": 650, "hpMax": 750, "pw": 100, "pwMax": 100, "ptype": 3, "tok": "ENERGY"},
]


def shapes():
    """name -> dict(n = group size incl. you, pet, mode, pick, dead, oor)"""
    out = {}
    for n in (2, 3, 4, 5):
        dead = 2 if n >= 3 else None                 # one dead (party2) from a party of 3
        oor = (n - 1) if n >= 4 else None             # one out of range (the last member) from a party of 4
        for pet in (False, True):
            for mode in (False, True):
                nm = "party%d%s%s" % (n, "_pet" if pet else "", "_mode" if mode else "")
                out[nm] = dict(n=n, pet=pet, mode=mode, pick=("party1" if mode else None), dead=dead, oor=oor)
    out["party5_pet_mode_you"] = dict(n=5, pet=True, mode=True, pick="player", dead=2, oor=4)
    out["party5_pet_mode_pet"] = dict(n=5, pet=True, mode=True, pick="pet", dead=2, oor=4)
    out["party2_k768"] = dict(n=2, pet=False, mode=False, pick=None, dead=None, oor=None, uih=768)
    return out


class Rig:
    """One runtime: Trail + Hub (src), their driver frames, a step."""

    def __init__(self, hub_src=None):
        L = tc.lupa.LuaRuntime()
        self.L = L
        L.execute(tc.STUB)
        L.execute(PRE)
        L.execute(tp.GAMEPLUS)
        L.execute(GEO)
        L.execute(GAME2)
        L.execute(open(tc.TRAIL, encoding="utf-8").read().replace("local ADDON = ...", "local ADDON = 'WowCompanion'", 1))
        G = L.globals()
        self.G = G
        self.trailEv = [r for r in self._frames() if r.events and r.events.UNIT_AURA and r.scripts][0]
        L.execute(HUBSTUB)
        L.execute(PADMAP)
        n0 = len(G.ALLR)
        src = hub_src if hub_src is not None else open(HUB, encoding="utf-8").read()
        L.execute(src.replace("local ADDON = ...", "local ADDON = 'WowCompanionHub'", 1))
        new = [G.ALLR[i] for i in range(n0 + 1, len(G.ALLR) + 1)]
        self.hubDrv = [r for r in new if r.events and r.events.GROUP_ROSTER_UPDATE and r.scripts and r.scripts.OnUpdate][0]
        self.UF = G.WowCompanion.uf
        self.PF = self.UF.pf
        self.P = G.WowCompanion.prd
        G.WowCompanion.npWant = L.table_from({"hud": True})
        G.GAME.t = 1000.0
        self.fire("PLAYER_ENTERING_WORLD")

    def _frames(self):
        A = self.G.ALLR if self.G.ALLR else self.G.FRAMES
        return [A[i] for i in range(1, len(A) + 1)]

    def fire(self, e, a=None):
        self.trailEv.scripts.OnEvent(self.trailEv, e, a)
        if getattr(self, "hubDrv", None) is not None:
            self.hubDrv.scripts.OnEvent(self.hubDrv, e, a)

    def run(self, secs, dt=1 / 30):
        for _ in range(int(round(secs / dt))):
            self.G.GAME.t = self.G.GAME.t + dt
            self.trailEv.scripts.OnUpdate(self.trailEv, dt)
            self.hubDrv.scripts.OnUpdate(self.hubDrv, dt)

    def set_k(self, uih):
        """UIParent's height (K = uih / 1080 in Hub), then a fresh PLAYER_ENTERING_WORLD (Hub's K recompute)."""
        self.G.UIParent.hh = uih
        self.G.UIParent.ww = uih * 16 / 9
        self.fire("PLAYER_ENTERING_WORLD")
        self.run(1.0)

    def scene(self, sh):
        L, G = self.L, self.G
        units = {}
        for i in range(1, sh["n"]):
            m = dict(MEMBERS[i - 1])
            if sh.get("dead") == i:
                m["dead"] = True
            units["party%d" % i] = m
        units["target"] = {"name": "Kobold Tunneler", "lv": 5, "hp": 410, "hpMax": 600, "pw": 0, "pwMax": 0, "hostile": True}
        G.UNITS = L.table_from({k: L.table_from(v) for k, v in units.items()})
        G.TARGET = "target"
        order = ["player"] + (["pet"] if sh["pet"] else []) + ["party%d" % i for i in range(1, sh["n"])]
        G.PM.units = L.table_from(order)
        G.PM.on = sh["mode"]
        G.PM.u = sh.get("pick")
        G.PM.rng = L.table_from({("party%d" % sh["oor"]): False} if sh.get("oor") else {})
        G.PMP = None
        if sh["pet"]:
            G.PET = L.table_from({"hp": 300, "hpMax": 500, "pw": 60, "pwMax": 100, "ptype": 2, "tok": "FOCUS", "name": "Wolf",
                                  "icon": 132203, "hunter": True})
        else:
            G.PET = None
        G.PLAYERCLS = sh.get("cls", "SHAMAN")
        if sh.get("y") is not None:                       # the HUD's own height setting (spec px from the centre)
            self.P.y = sh["y"]
        G.ME.fight = True
        G.ME.hp = 620
        if sh.get("buffs"):
            t = G.GAME.t
            mk = lambda i, d, left, kind=None: L.table_from(dict({"icon": i, "duration": d, "expirationTime": t + left},
                                                                  **({"dispelName": kind} if kind else {})))
            G.ME.buffs = L.table_from([mk(135953, 15, 9), mk(136081, 12, 6), mk(132333, 120, 100), mk(136085, 60, 40)])
            G.ME.debuffs = L.table_from([mk(136134, 18, 10, "Magic"), mk(136139, 30, 22, "Poison")])
            self.fire("UNIT_AURA", "player")
        self.fire("PLAYER_REGEN_DISABLED")
        self.fire("UNIT_PET", "player")
        self.fire("GROUP_ROSTER_UPDATE")
        self.fire("PLAYER_TARGET_CHANGED")
        self.run(1.0)
        if sh.get("uih"):
            self.set_k(sh["uih"])

    def roots(self):
        L, G = self.L, self.G
        rt = L.table()
        for i in range(1, 5):
            f = self.PF.frames[i]
            if f is not None:
                rt[f] = "party%d" % i
        if self.PF.tab is not None:
            rt[self.PF.tab] = "tab"
        if self.UF.tb.f is not None:
            rt[self.UF.tb.f] = "target"
        if self.P.f is not None:
            rt[self.P.f] = "hud"
        return rt

    def items(self):
        got = self.G.COLLECT(self.roots())
        return [dict(got[i]) for i in range(1, len(got) + 1)]

    def rect(self, region):
        self.G.RECT_RESET()
        q = self.G.RECT(region)
        return (q[1], q[2], q[3], q[4])

    def party_bars(self):
        """{ 'party1': { 'hp': rect, 'mp': rect, 'side': ..., 'row': ... } } for the rows shown"""
        out = {}
        for i in range(1, 5):
            f = self.PF.frames[i]
            if f is not None and f.shown is not False:
                out["party%d" % i] = {"hp": self.rect(f.hp), "mp": self.rect(f.mp), "frame": self.rect(f),
                                      "side": f.look.side, "row": f.look.row}
        return out


# ---------------------------------------------------------------------------------------------------------- drawing
def _rgb(c, a=1.0):
    if c is None:
        return None
    v = [c[i] for i in range(1, 5)] if not isinstance(c, (list, tuple)) else list(c)
    v = [x if isinstance(x, (int, float)) else 1 for x in v if x is not None]
    while len(v) < 3:
        v.append(1)
    al = (v[3] if len(v) > 3 else 1) * a
    return (int(255 * max(0, min(1, v[0]))), int(255 * max(0, min(1, v[1]))), int(255 * max(0, min(1, v[2]))),
            int(255 * max(0, min(1, al))))


def _lua_list(t):
    if t is None:
        return None
    try:
        return [t[i] for i in range(1, 5) if t[i] is not None]
    except Exception:
        return None


def draw(items, path, title, notes=()):
    from PIL import Image, ImageDraw, ImageFont
    W, H = 1920, 1080
    img = Image.new("RGBA", (W, H))
    g = ImageDraw.Draw(img)
    for y in range(H):                                    # a dusk sky over a dim field
        k = y / H
        g.line([(0, y), (W, y)], fill=(int(28 + 22 * k), int(36 + 30 * k), int(48 + 8 * k), 255))
    g.ellipse([600, 640, 1400, 1300], fill=(44, 62, 40, 255))
    g.ellipse([-300, 760, 700, 1400], fill=(40, 56, 38, 255))
    over = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    od = ImageDraw.Draw(over)
    fonts = {}

    def font(s):
        s = max(6, int(round(s)))
        if s not in fonts:
            try:
                fonts[s] = ImageFont.truetype("arialbd.ttf", s)
            except OSError:
                fonts[s] = ImageFont.load_default()
        return fonts[s]

    def box(l, t, r, b):
        return [l, H - t, r, H - b]

    for it in items:
        l, b, r, t, a = it["l"], it["b"], it["r"], it["t"], it["alpha"]
        if it["kind"] == "fs":
            tx = it.get("text")
            if tx is None or str(tx) == "":
                continue
            tx = str(tx)
            fz = font((it.get("fsize") or 12) * 1.0)
            col = _rgb(_lua_list(it.get("tcol")) or [1, 1, 1], a)
            tw = od.textlength(tx, font=fz)
            jh = it.get("jh") or "CENTER"
            x = l if jh == "LEFT" else (r - tw if jh == "RIGHT" else (l + r - tw) / 2)
            y = H - (t + b) / 2
            od.text((x, y), tx, font=fz, fill=col, anchor="lm", stroke_width=1, stroke_fill=(0, 0, 0, col[3]))
            continue
        tint = _lua_list(it.get("col")) or _lua_list(it.get("sbc"))
        ct, at, tex = _lua_list(it.get("ct")), it.get("atlas"), it.get("tex")
        bx = box(l, t, r, b)
        bgart = "Bar-BG" in str(at or tex or "")
        if ct:
            c = list(ct)
            if tint:
                c = [c[i] * tint[i] for i in range(3)] + c[3:]
            od.rectangle(bx, fill=_rgb(c, a * (0.3 if it.get("grad") else 1)))
        elif bgart:
            # the HUD bar's own frame art: its track (or the whole frame) dark, ringed by the rim; the lip / side slices are that rim
            if it.get("layer") == "BACKGROUND" or at:
                od.rectangle(box(l - 2, t + 2, r + 2, b - 2), outline=_rgb([0.72, 0.58, 0.3, 1], a), width=2)
                od.rectangle(bx, fill=_rgb([0.09, 0.08, 0.06, 0.92], a))
        elif isinstance(tex, str) and "compass_band" in tex:
            od.rectangle(bx, fill=_rgb([0, 0, 0, 0.3], a))
        elif isinstance(tex, str) and tex.startswith("atlas:") and "sidetab" in tex:
            lit = "selected" in tex
            od.rectangle(bx, fill=_rgb([0.42, 0.3, 0.13, 0.95] if lit else [0.22, 0.16, 0.1, 0.95], a),
                         outline=_rgb([0.95, 0.78, 0.3, 1] if lit else [0.5, 0.4, 0.22, 1], a))
        elif at or tex is not None:
            name = str(at or tex)
            c = tint or [0.85, 0.85, 0.85]
            od.rectangle(bx, fill=_rgb(list(c[:3]) + [0.92], a))
            if (r - l) > 40 and (t - b) > 9 and not it.get("sbc"):
                od.text((l + 2, H - (t + b) / 2), name.split("\\")[-1][:int((r - l) / 6)], font=font(9), fill=(20, 20, 20, int(200 * a)),
                        anchor="lm")
            elif not it.get("sbc") and (r - l) <= 40:
                od.rectangle(bx, outline=_rgb([0, 0, 0, 0.6], a))
        else:
            od.rectangle(bx, outline=_rgb([1, 0, 1, 1], a))
    img = Image.alpha_composite(img, over)
    g = ImageDraw.Draw(img)
    g.text((1900, 1040), title, font=font(26), fill=(235, 235, 235, 255), anchor="rs", stroke_width=2, stroke_fill=(0, 0, 0, 255))
    for i, n in enumerate(notes):
        g.text((1900, 1000 - 30 * i), n, font=font(20), fill=(220, 220, 200, 255), anchor="rs", stroke_width=2, stroke_fill=(0, 0, 0, 255))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.convert("RGB").save(path)


def sheet(paths, out, cols=4, crop=None):
    from PIL import Image, ImageDraw, ImageFont
    tw, th = 640, 360
    rows = (len(paths) + cols - 1) // cols
    s = Image.new("RGB", (cols * tw, rows * (th + 26)), (12, 12, 14))
    d = ImageDraw.Draw(s)
    try:
        f = ImageFont.truetype("arialbd.ttf", 18)
    except OSError:
        f = ImageFont.load_default()
    for i, p in enumerate(paths):
        im = Image.open(p)
        im = (im.crop(crop) if crop else im).resize((tw, th))
        x, y = (i % cols) * tw, (i // cols) * (th + 26)
        s.paste(im, (x, y + 26))
        d.text((x + 6, y + 4), os.path.splitext(os.path.basename(p))[0], font=f, fill=(230, 230, 230))
    s.save(out)


def render(rig, sh, path, title):
    rig.scene(sh)
    bars = rig.party_bars()
    widths = ["%s %s w%.0f" % (u, v["side"], v["hp"][2] - v["hp"][0]) for u, v in sorted(bars.items())]
    draw(rig.items(), path, title, notes=widths)
    return bars


WIDTH_LINE = "local w = math.min(PF.W_BAR, PF.COL_W - PF.BAR_X - 30)"


def width_options(out, widths=(194, 228, 300), names=("party2", "party3_mode", "party4_pet")):
    """A full row's bar at each width (a shared row's halves stay W_BAR); one PNG per (width, shape), then one sheet, a column per width."""
    base = open(HUB, encoding="utf-8").read()
    assert WIDTH_LINE in base
    paths = []
    all_sh = shapes()
    for nm in names:
        for w in widths:
            src = base.replace(WIDTH_LINE, 'local w = (side == "full") and %d or math.min(PF.W_BAR, PF.COL_W - PF.BAR_X - 30)' % w, 1)
            p = os.path.join(out, "top_wopt_%d_%s.png" % (w, nm))
            render(Rig(src), all_sh[nm], p, "full row %d px  %s" % (w, nm))
            paths.append(p)
    sheet(paths, os.path.join(out, "top_width_options.png"), cols=len(widths), crop=(0, 0, 960, 540))
    print(os.path.join(out, "top_width_options.png"))


# hub42 / prd15 (the user, 2026-11-12): the redesign's shots - out of Party mode the members in ONE column top left; Party mode: they
# fade out and back in under the HUD bar ([me][pet] / [p1][p2] / [p3][p4]), the pet strip beside the HP bar, the lit side tab at the
# block's left edge; and the move caught mid-fade
SHOTS = "C:/Users/Jrees/AppData/Local/Temp/claude/6c5148a90427b4bc/789b5138-341f-4ef8-8102-099551ca3a8a/scratchpad/party-top-shots"


def party_top_shapes():
    return {
        "off_1member": dict(n=2, pet=True, mode=False, pick=None, dead=None, oor=None),
        "off_2members": dict(n=3, pet=True, mode=False, pick=None, dead=None, oor=None),
        "off_4members": dict(n=5, pet=True, mode=False, pick=None, dead=None, oor=4),
        "mode_pet_4members": dict(n=5, pet=True, mode=True, pick="party1", dead=None, oor=4),
        "mode_pet_2members": dict(n=3, pet=True, mode=True, pick="party2", dead=None, oor=None),
        "mode_nopet_4members": dict(n=5, pet=False, mode=True, pick="party3", dead=None, oor=None),
        "mode_pet_4members_pick_pet": dict(n=5, pet=True, mode=True, pick="pet", dead=None, oor=None),
        "mode_rogue_4members_combo": dict(n=5, pet=False, mode=True, pick="party1", dead=None, oor=None, cls="ROGUE"),
        "off_rogue_4members_combo": dict(n=5, pet=False, mode=False, pick=None, dead=None, oor=None, cls="ROGUE"),
        "mode_low_hud_4members": dict(n=5, pet=True, mode=True, pick="party1", dead=None, oor=None, y=-420),
        "mode_2members_nolift": dict(n=3, pet=True, mode=True, pick="party1", dead=None, oor=None),
        "mode_pet_3members": dict(n=4, pet=True, mode=True, pick="party1", dead=None, oor=None),
        "mode_solo_pet": dict(n=1, pet=True, mode=True, pick="player", dead=None, oor=None),
        "off_4members_buffs": dict(n=5, pet=True, mode=False, pick=None, dead=None, oor=None, buffs=True),
        "mode_pet_4members_buffs": dict(n=5, pet=True, mode=True, pick="party1", dead=None, oor=None, buffs=True),
    }


def party_top(out):
    done = []
    for nm, sh in party_top_shapes().items():
        p = os.path.join(out, "top_%s.png" % nm)
        render(Rig(), sh, p, nm)
        print(p)
        done.append(p)
    # the move caught mid-way: Party mode turned on from the left column (n 5 with the pet); 0.2 s in = fading out of the column,
    # 0.45 s in = fading in under the HUD (FADE_S 0.2 each way, from the next 0.1 s step)
    for secs, nm in ((0.2, "midfade_out"), (0.45, "midfade_in")):
        rig = Rig()
        rig.scene(party_top_shapes()["off_4members"])
        rig.G.PM.on = True
        rig.G.PM.u = "party1"
        rig.run(secs)
        p = os.path.join(out, "top_%s.png" % nm)
        draw(rig.items(), p, "%s (%.1f s after Select)" % (nm, secs),
             notes=["block alpha %.2f at %s" % (rig.PF.fa, rig.PF.at), "pet strip alpha %s, %s" % (rig.P.petFadeA, rig.P.petSide)])
        print(p)
        done.append(p)
    sheet(done, os.path.join(out, "top_party_sheet.png"), cols=3)
    print(os.path.join(out, "top_party_sheet.png"))


def main():
    a = sys.argv[1:]
    if "--party-top" in a:
        party_top(a[a.index("--out") + 1] if "--out" in a else SHOTS)
        return
    out = a[a.index("--out") + 1] if "--out" in a else OUT
    rev = a[a.index("--rev") + 1] if "--rev" in a else None
    tag = a[a.index("--tag") + 1] if "--tag" in a else (rev or "")
    want = a[a.index("--shapes") + 1].split(",") if "--shapes" in a else None
    src = None
    if rev:
        src = subprocess.run(["git", "-C", ROOT, "show", "%s:%s" % (rev, HUB_REL)], capture_output=True, check=True).stdout.decode("utf-8")
    if "--width-options" in a:
        width_options(out)
        return
    all_sh = shapes()
    names = want or list(all_sh)
    done = []
    for nm in names:
        rig = Rig(src)                                   # a fresh game per shape (nothing carried over)
        fn = ("top_%s_%s.png" % (tag, nm)) if rev else ("top_%s.png" % nm)
        p = os.path.join(out, fn)
        bars = render(rig, all_sh[nm], p, ("%s  %s" % (tag, nm)) if rev else nm)
        print(p, {u: round(v["hp"][2] - v["hp"][0]) for u, v in bars.items()})
        done.append(p)
    if not rev and want is None:
        sheet(done, os.path.join(out, "top_sheet.png"))
        print(os.path.join(out, "top_sheet.png"))


if __name__ == "__main__":
    main()
