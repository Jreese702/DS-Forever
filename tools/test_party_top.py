"""The TOP screen's party block (WowCompanionHub/Hub.lua hub35; party_design.md D, E, the user's decisions and ADAPTIVE ROWS 2026-11-11), off-device:
Hub.lua under a stub game whose frames remember what was drawn, PadMap's exports stubbed (WC.padMap.party: on, box, lb, rng, selWant).

    python tools/test_party_top.py

Checks the block (hub42, the user 2026-11-12: only party1..4 - out of Party mode ONE column top left, party1..4 top to bottom; in Party
mode faded out, moved and faded in UNDER the HUD bar, [party1][party2] / [party3][party4], the pet strip beside the HP bar (Trail prd15);
every bar one column's; no self row; a raid or our switch off: nothing),
Party mode's looks (each row's LB glyph, the gold ring on the pick box.u, the white chevron at your target; off: a white ring on your
target), dead / offline / out of range (the shared rng: 0.45), the HUD green re-tinted every step, the secret health only into the
widget setters, and the Select tab (hub42, the user's pick B: only in Party mode, lit, at the grid's left edge; hub38: the game's side tab
in three slices, lit by its selected art). Needs lupa. Exit code 1 on any failure."""
import os
import sys

from lupa import lua51 as lupa

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HUB = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons", "WowCompanionHub", "Hub.lua")

STUB = r'''
SENT, ROWS = {}, {}
local Frame = {}
Frame.__index = function(t, k) local v = rawget(Frame, k); if v ~= nil then return v end; return function() end end
local function mk(kind, name) local f = setmetatable({ kind = kind, name = name, shown = (kind ~= "Frame" and kind ~= "StatusBar") and true or false, alpha = 1 }, Frame); return f end
function Frame:SetAlpha(a) self.alpha = a end
function Frame:GetAlpha() return self.alpha or 1 end
function Frame:Show() self.shown = true end
function Frame:Hide() self.shown = false end
function Frame:IsShown() return self.shown end
function Frame:SetText(t) if type(t) == "table" then error("a secret value into SetText") end; self.text = t end
function Frame:SetTextColor(r, g, b) self.tc = { r, g, b } end
function Frame:SetColorTexture(r, g, b, a) self.ct = { r, g, b, a } end
function Frame:SetVertexColor(r, g, b) self.vc = { r, g, b } end
function Frame:SetPoint(...) local l = rawget(self, "anch") or {}; l[#l + 1] = { ... }; self.anch = l end
function Frame:ClearAllPoints() self.anch = nil end
function Frame:SetStatusBarColor(r, g, b) self.sbc = { r, g, b }; self.paints = (rawget(self, "paints") or 0) + 1 end
function Frame:SetStatusBarTexture(t) self.sbt = t end
function Frame:GetStatusBarTexture() return self.sbt end
function Frame:SetMinMaxValues(a, b) self.mm = { a, b } end
function Frame:SetValue(v) self.val = v end
function Frame:SetAtlas(a) if not ATLAS[a] then error("no atlas " .. tostring(a)) end; self.atlas = a end
function Frame:SetTexture(t) self.tex = t end
function Frame:SetTexCoord(...) self.tc = { ... } end
function Frame:SetHeight(h) self.h = h end
function Frame:CreateTexture() local t = mk("Texture"); CHILD[#CHILD + 1] = t; return t end
function Frame:CreateFontString() local t = mk("FontString"); return t end
function Frame:GetParent() return self.parent end
function Frame:GetFrameLevel() return 1 end
function Frame:GetHeight() return 1080 end
function Frame:GetWidth() return 1920 end
function Frame:HookScript() end
function Frame:RegisterEvent() return true end
function Frame:SetScript(k, fn) self[k] = fn end
CHILD = {}
function CreateFrame(kind, name, parent) local f = mk(kind, name); f.parent = parent; if name then _G[name] = f end; MADE[#MADE + 1] = f; return f end
MADE = {}
ATLAS = { ["Gamepad_Rev_Minus_64"] = true, ["Gamepad_Rev_A_64"] = true, ["Gamepad_Gen_Up_64"] = true, ["Gamepad_Gen_Down_64"] = true }
-- hub38: the game's side tab pieces (the kit's side_tab / side_tab_on: 110 x 120 on their sheet)
C_Texture = { GetAtlasInfo = function(a)
    if a == "common-sidetab-c60-2x" or a == "common-sidetab-selected-c60-2x" then
        return { file = 7000001, leftTexCoord = 0.1, rightTexCoord = 0.2, topTexCoord = 0.5, bottomTexCoord = 0.7, width = 110, height = 120 }
    end
end }
UIParent = mk("Frame", "UIParent")
for _, n in ipairs({ "PlayerFrame", "PetFrame", "TargetFrame", "TargetFrameToT" }) do _G[n] = setmetatable({ alpha = 1 }, Frame) end
function hooksecurefunc() end
SlashCmdList = {}
function InCombatLockdown() return false end
function GetTime() return 100 end
function GetCurrentKeyBoardFocus() return nil end
SECRET = setmetatable({}, { __eq = function() error("a secret compared") end, __lt = function() error("a secret compared") end,
                             __tostring = function() error("a secret read") end })
WowCompanion = {
    S = function(fn) local ok, v = pcall(fn); if ok then return v end end,
    send = function(j) SENT[#SENT + 1] = j end, encode = function(t) return t end, push = function() end,
    pad = { op = function(name, ns, fn) ROWS[name] = fn; return 1 end, describe = function() return {} end },
    snapshot = function() end,
    isPlain = function(v) return not rawequal(v, SECRET) end,
}
UNITS = { player = { name = "Korvash", cls = "SHAMAN", lv = 20 } }
RAID = false
TARGET = nil
function UnitName(u) local x = UNITS[u]; return x and x.name end
function UnitClass(u) local x = UNITS[u]; if x then return x.cls, x.cls end end
function UnitLevel(u) local x = UNITS[u]; return x and x.lv end
function UnitIsConnected(u) local x = UNITS[u]; return x ~= nil and not x.off end
function UnitIsDeadOrGhost(u) local x = UNITS[u]; return x ~= nil and x.dead == true end
function UnitIsGhost() return false end
function UnitIsUnit(a, b) return b == "target" and a == TARGET end
function UnitHealth() return SECRET end
function UnitHealthMax() return SECRET end
function UnitPower() return SECRET end
function UnitPowerMax() return SECRET end
function UnitPowerType(u) return 0, "MANA" end
function UnitInRange() error("Hub reads PadMap's one rng, never UnitInRange itself") end
function IsInRaid() return RAID end
function GetNumSubgroupMembers() local n = 0; for k in pairs(UNITS) do if k:match("^party%d$") then n = n + 1 end end; return n end
RAID_CLASS_COLORS = { SHAMAN = { r = 0, g = 0.44, b = 0.87 }, WARRIOR = { r = 0.78, g = 0.61, b = 0.43 }, MAGE = { r = 0.25, g = 0.78, b = 0.92 } }
PM = { on = false, u = nil, sel = "bound", rng = {} }
WowCompanion.padMap = { party = {
    on = function() return PM.on end,
    box = function() return { u = PM.u } end,
    lb = function() return { self = "PAD1", p1 = "PADDUP", p2 = "PADDDOWN" } end,
    rng = function(u) return PM.rng[u] end,
    units = function() return PM.units end,
    selWant = function() return PM.sel end,
} }
'''


def grid(k):
    """hub42 (the user's answer, 2026-11-12): Party mode's rows under the HUD for k members -> { i: (row, side) }:
    1 [p1]; 2 [p1][p2]; 3 [p1] / [p2][p3]; 4 [p1][p2] / [p3][p4]"""
    if k == 3:
        return {1: (0, "left"), 2: (1, "left"), 3: (1, "right")}
    return {i: ((i - 1) // 2, "left" if i % 2 == 1 else "right") for i in range(1, k + 1)}


RING_CHECK = '''(function()
    local f = WowCompanion.uf.pf.frames[1]
    local r = f.ring
    local a = r.area and r.area.anch or {}
    if #a ~= 2 or a[1][1] ~= "TOPLEFT" or not rawequal(a[1][2], f.hp) or a[2][1] ~= "BOTTOMRIGHT" or not rawequal(a[2][2], f.mp) then
        return "area"
    end
    local want = { { "TOPLEFT", "TOPRIGHT" }, { "BOTTOMLEFT", "BOTTOMRIGHT" }, { "TOPLEFT", "BOTTOMLEFT" }, { "TOPRIGHT", "BOTTOMRIGHT" } }
    for i = 1, 4 do
        local e = r.t[i].anch or {}
        if #e ~= 2 then return "edge" .. i end
        for j = 1, 2 do
            if e[j][1] ~= want[i][j] or e[j][3] ~= want[i][j] or not rawequal(e[j][2], r.area) then return "edge" .. i .. "." .. j end
        end
    end
    return "ok"
end)()'''


def main():
    fails = []

    def chk(name, ok, got=''):
        print(("PASS " if ok else "FAIL ") + name + ((" -> " + str(got)) if got != '' and not ok else ''))
        if not ok:
            fails.append(name)

    L = lupa.LuaRuntime()
    L.execute(STUB)
    L.execute(open(HUB, encoding="utf-8").read().replace("local ADDON = ...", "local ADDON = 'WowCompanionHub'", 1))
    G = L.globals()
    UF = G.WowCompanion.uf
    PF = UF.pf
    step = UF.partyStep

    def frames():
        out = {}
        for i in range(1, 5):
            f = PF.frames[i]
            if f is not None and f.shown:
                out[i] = f
        return out

    def where(f):
        return {i: (f[i].look.row, f[i].look.side) for i in f}

    src = open(HUB, encoding="utf-8").read()
    chk("hub42: the version tag (hub38 the player / pet row, the game's side tab; hub40 the bars one column wide; hub41 pitch 64; hub42 the "
        "left column / the grid under the HUD, the fade, the tab beside the grid)", 'local HV = "hub42"' in src)
    chk("hub34: the combat log's switch calls are gone (PadMap m129)", 'WC.clogSync' not in src and 'WC.clogTicker' not in src)
    chk("hub42: the fade runs every frame from the driver (PF.fadeTick on drv's OnUpdate), alpha only, FADE_S %s s" % PF.FADE_S,
        'pcall(UF.pf.fadeTick, el)' in src and 0.15 <= PF.FADE_S <= 0.25)
    chk("hub42: the old flush-left tab place is gone (no TAB_X)", PF.TAB_X is None and 'TAB_X' not in src)

    def settle():
        step()
        for _ in range(40):
            PF.fadeTick(0.05)
        step()

    def anch(f):
        a = f.anch[1]
        return (a[1], "UIParent" if G.rawequal(a[2], G.UIParent) else a[2], a[3], a[4], a[5])

    # solo, Party mode off: nothing (no row, no tab)
    G.WowCompanion.partyOk = True
    settle()
    chk("solo, Party mode off: no row, no tab", frames() == {} and PF.rows == 0 and (PF.tab is None or not PF.tab.shown), PF.rows)
    # hub35 / hub42 (the user, 2026-11-12: "the player should not have their HP bar on the right side with party mode any more"): NO self
    # row in any mode; solo with Party mode on only the tab (beside the [me][pet] row) says it is on
    G.PM.on = True
    G.PM.u = "player"
    settle()
    chk("hub42: solo, Party mode on: no row at all (you = the HUD's bars), the Select tab on, under the HUD", frames() == {} and PF.rows == 0
        and PF.tab is not None and PF.tab.shown and PF.tabLook == "on" and PF.at == "hud", (list(frames()), PF.tabLook, PF.at))
    sg = PF.g
    chk("hub42 (the user's answer): solo, the tab BESIDE the [me][pet] row - TOPRIGHT left of the HP bar's rim, the bars' height + pads",
        PF.tab.solo is True and PF.tab.anch[1][1] == "TOPRIGHT" and PF.tab.anch[1][3] == "CENTER"
        and abs(PF.tab.anch[1][4] - (sg.x - PF.TAB_GAP - PF.SOLO_RIM)) < 1e-9 and abs(PF.tab.anch[1][5] - (sg.top + PF.SOLO_PAD)) < 1e-9
        and abs(PF.tab.h - (sg.h + 2 * PF.SOLO_PAD)) < 1e-9, (PF.tab.anch[1][4], PF.tab.anch[1][5], PF.tab.h))
    chk("hub35: no frame is ever made for you or your pet (no WowCompanionUFParty0 / 5)", G.WowCompanionUFParty0 is None
        and G.WowCompanionUFParty5 is None, "")
    # OUT of Party mode: ONE column top left, party1..4 top to bottom
    L.execute('UNITS.party1 = { name = "Brannoc", cls = "WARRIOR", lv = 21 }; UNITS.party2 = { name = "Iselle", cls = "MAGE", lv = 19 }; '
              'PM.on = false; PM.u = "party1"; TARGET = "party2"')
    settle()
    f = frames()
    chk("hub42: Party mode off: ONE column top left - party1 row 0, party2 row 1, each on the left, a column's bar (you are not drawn)",
        where(f) == {1: (0, "left"), 2: (1, "left")} and f[1].look.w == PF.W_BAR and PF.at == "left", where(f))
    chk("hub42: ... placed from the screen's top left (x %s, y -%s - row * %s)" % (PF.X, PF.Y, PF.PITCH),
        anch(f[1]) == ("TOPLEFT", "UIParent", "TOPLEFT", PF.X, -PF.Y) and anch(f[2]) == ("TOPLEFT", "UIParent", "TOPLEFT", PF.X, -(PF.Y + PF.PITCH)),
        (anch(f[1]), anch(f[2])))
    chk("Party mode off: no LB glyph (the slot kept empty), a WHITE ring on your target (party2), no gold ring",
        all(f[i].look.glyph is None for i in f) and f[2].look.ring == "white" and f[1].look.ring is None
        and not any(f[i].look.chev for i in f), [(f[i].look.ring, f[i].look.glyph) for i in sorted(f)])
    chk("names in class colour, the level in its box", f[1].name.text == "Brannoc" and abs(f[1].name.tc[1] - 0.78) < 1e-6 and f[1].lvl.text == 21,
        (f[1].name.text, f[1].lvl.text))
    chk("hub42: Party mode off: NO top Select tab (the user's pick B)", PF.tab is None or not PF.tab.shown, PF.tabLook)
    # THE FADE (the user: "fade out, move, fade in"): Select turns Party mode on
    G.PM.on = True
    step()
    f = frames()
    chk("hub42: Party mode on: first a fade OUT where the block stands (still the left column, fading)", PF.fdir == -1 and PF.at == "left"
        and where(f) == {1: (0, "left"), 2: (1, "left")}, (PF.fdir, PF.at))
    PF.fadeTick(0.1)
    chk("hub42: ... half way out: the rows at alpha %.2f" % f[1].alpha, abs(PF.fa - 0.5) < 1e-6 and abs(f[1].alpha - 0.5) < 1e-6
        and PF.at == "left", (PF.fa, f[1].alpha))
    PF.fadeTick(0.1)
    f = frames()
    chk("hub42: ... unseen: moved under the HUD ([party1][party2] side by side), alpha 0, now fading IN",
        PF.at == "hud" and PF.fdir == 1 and PF.fa == 0 and where(f) == {1: (0, "left"), 2: (0, "right")} and f[1].alpha == 0, (PF.at, where(f)))
    PF.fadeTick(0.1)
    chk("hub42: ... half way in (%.2f), the tab fading in with the rows" % f[1].alpha, abs(f[1].alpha - 0.5) < 1e-6
        and PF.tab.shown and abs(PF.tab.alpha - 0.5) < 1e-6, (f[1].alpha, PF.tab.alpha))
    PF.fadeTick(0.1)
    chk("hub42: ... done: alpha 1, no fade running", PF.fa == 1 and PF.fdir is None and f[1].alpha == 1, (PF.fa, PF.fdir))
    g = PF.g
    chk("hub42: under the HUD: party1's bar under the HP bar's left (TOPLEFT on UIParent's CENTER at geo.x - BAR_X), party2 one column right",
        anch(f[1]) == ("TOPLEFT", "UIParent", "CENTER", g.x - PF.BAR_X, g.y)
        and anch(f[2]) == ("TOPLEFT", "UIParent", "CENTER", g.x - PF.BAR_X + PF.COL_W + PF.COL_GAP, g.y), (anch(f[1]), anch(f[2])))
    # a turn back mid-fade: in again where it stands
    G.PM.on = False
    step()
    PF.fadeTick(0.05)
    G.PM.on = True
    step()
    chk("hub42: Party mode back on mid-fade-out: fades in again where it stands (under the HUD)", PF.fdir == 1 and PF.at == "hud", (PF.fdir, PF.at))
    settle()
    f = frames()
    chk("Party mode on: each row's LB glyph (p1, p2)", [f[i].look.glyph for i in sorted(f)] == ["PADDUP", "PADDDOWN"],
        [f[i].look.glyph for i in sorted(f)])
    chk("Party mode on: the GOLD ring on the pick (party1), none on your target; the white chevron at your target (party2)",
        f[1].look.ring == "gold" and f[2].look.ring is None and f[2].look.chev is True and f[1].look.chev is False,
        [(f[i].look.ring, f[i].look.chev) for i in sorted(f)])
    chk("the ring's gold is the bottom box's (1, 0.82, 0)", f[1].ring.t[1].ct[0 + 1] == 1 and abs(f[1].ring.t[1].ct[2] - 0.82) < 1e-6, "")
    rg = L.eval(RING_CHECK)
    chk("hub35 review: the ring on one area (health's top-left to power's bottom-right), every edge on that area alone", rg == "ok", rg)
    G.PM.u = "player"
    step()
    f = frames()
    chk("hub35: the pick on you: no ring on any party row (the HUD's bars wear it)", all(f[i].look.ring is None for i in f),
        [f[i].look.ring for i in sorted(f)])
    G.PM.u = "party1"
    # every shape: out of Party mode one column, in it the grid two a row; you and your pet never drawn
    L.execute('UNITS.party3 = { name = "Doran", cls = "WARRIOR", lv = 20 }; UNITS.party4 = { name = "Mira", cls = "MAGE", lv = 20 }')
    shapes = [
        (["player", "party1", "party2", "party3", "party4"], 4, "n 5, no pet"),
        (["player", "pet", "party1", "party2", "party3", "party4"], 4, "n 6 with the pet"),
        (["player", "pet", "party1", "party2", "party3"], 3, "n 5 with the pet"),
        (["player", "party1", "party2", "party3"], 3, "n 4, no pet"),
        (["player", "pet", "party1", "party2"], 2, "n 4 with the pet"),
        (["player", "pet", "party1"], 1, "n 3 with the pet"),
        (["player", "party4", "party2", "party1", "party3"], 4, "PadMap's list out of order: the bottom's order party1..4 still"),
    ]
    for units, k, why in shapes:
        G.PM.units = L.table_from(units)
        for mode in (False, True):
            G.PM.on = mode
            settle()
            f = frames()
            if mode:
                want = grid(k)
                rows = max(r for r, _ in want.values()) + 1
            else:
                want = {i: (i - 1, "left") for i in range(1, k + 1)}
                rows = k
            chk("hub42: %s, Party mode %s: %s" % (why, "on (the grid under the HUD)" if mode else "off (one column)", want),
                where(f) == want and PF.rows == rows and all(f[i].look.at == ("hud" if mode else "left") for i in f)
                and all(f[i].look.w == PF.W_BAR for i in f), (where(f), PF.rows))
            chk("hub42: ... the tab %s" % ("beside the grid, the rows' full height" if mode else "absent"),
                (PF.tab.shown and abs(PF.tab.h - (rows * PF.PITCH - (PF.PITCH - PF.HP - PF.GAP - PF.MP) + 4)) < 1e-6) if mode
                else not PF.tab.shown, (PF.tab.shown, PF.tab.h))
    G.PM.units = None
    G.PM.on = False
    L.execute('UNITS.party3 = nil; UNITS.party4 = nil')
    settle()
    f = frames()
    chk("hub35: back to two members (PadMap's list gone: the plain count): one column again",
        where(f) == {1: (0, "left"), 2: (1, "left")} and not (PF.frames[3] and PF.frames[3].shown), where(f))
    # dead, offline, out of range (PadMap's rng)
    L.execute('UNITS.party1.dead = true; UNITS.party2.off = true')
    step()
    f = frames()
    chk("dead: the grey ground and the skull in the level box (the level hidden)", f[1].look.dead is True and f[1].deadG.shown and f[1].skull.shown
        and not f[1].lvl.shown, (f[1].deadG.shown, f[1].skull.shown))
    chk("offline: the row at 0.45; alive and in range: 1", f[2].alpha == 0.45 and f[1].alpha == 1, (f[2].alpha, f[1].alpha))
    L.execute('UNITS.party1.dead = nil; UNITS.party2.off = nil; PM.rng = { party1 = false }')
    step()
    f = frames()
    chk("out of range by PadMap's rng (false): 0.45; not known (nil): no dim", f[1].alpha == 0.45 and f[2].alpha == 1 and not f[1].deadG.shown,
        (f[1].alpha, f[2].alpha))
    G.PM.on = True
    step()
    PF.fadeTick(0.1)
    chk("hub42: a dimmed row fades with the block (0.45 x the fade: %.3f)" % f[1].alpha, abs(f[1].alpha - 0.45 * 0.5) < 1e-6, f[1].alpha)
    settle()
    f = frames()
    L.execute('PM.rng = {}')
    # the HUD's green, set every step; secret health only into the setters
    p0 = f[1].hp.paints or 0
    step()
    step()
    f = frames()
    chk("the health re-tinted every step (PRD.HEALTH_RGB's green)", (f[1].hp.paints or 0) >= p0 + 2 and abs(f[1].hp.sbc[2] - 0.86) < 1e-6,
        (p0, f[1].hp.paints))
    chk("the secret health went into the bar's setters only", G.rawequal(f[1].hp.val, G.SECRET) and G.rawequal(f[1].hp.mm[2], G.SECRET), "")
    # the top Select tab: Party mode on only, at the grid's left edge
    tab = PF.tab

    def shown(parts):
        return [p.shown for p in parts.values()]
    chk("hub38: the Select tab: on (Party mode on), the B glyph shown, the game's side tab lit (its selected art shown over it)",
        tab is not None and tab.shown and PF.tabLook == "on" and tab.b.shown and all(shown(tab.base)) and all(shown(tab.lit))
        and len(tab.base) == 3 and len(tab.lit) == 3, PF.tabLook)
    g = PF.g
    chk("hub42: the side tab is the game's atlas in three slices (no drawn fill / edge), at the grid's LEFT edge (TOPRIGHT TAB_GAP left of "
        "the first column, level with its first name), not on the screen's edge",
        all(p.tex == 7000001 for p in tab.base.values()) and G.rawget(tab, "bg") is None and G.rawget(tab, "edge") is None
        and anch(tab) == ("TOPRIGHT", "UIParent", "CENTER", g.x - (PF.BAR_X + PF.TAB_GAP), g.y - (PF.NAME_H - 4))
        and abs(tab.base[1].tc[3] - 0.5) < 1e-9 and abs(tab.base[3].tc[4] - 0.7) < 1e-9
        and abs(tab.base[1].h - PF.TAB_W * 120 * PF.TAB_CAP / 110) < 1e-6, anch(tab))
    G.PM.on = False
    settle()
    chk("hub42: Party mode off: the tab gone, the block back in the left column", not tab.shown and PF.at == "left", (tab.shown, PF.at))
    G.WowCompanion.partyOk = False
    G.PM.on = True
    settle()
    chk("Party mode not allowed (the switch off): no tab, the column stays top left", not tab.shown and len(frames()) == 2 and PF.at == "left", tab.shown)
    G.WowCompanion.partyOk = True
    # a raid: nothing; our switch off: nothing
    G.RAID = True
    settle()
    chk("in a raid: nothing (no row, no tab)", frames() == {} and not tab.shown, list(frames()))
    G.RAID = False
    UF.party = False
    settle()
    chk("our party frames switched off (SW_UFP): nothing", frames() == {} and not tab.shown, list(frames()))
    UF.party = True
    settle()
    chk("on again: two rows", len(frames()) == 2, len(frames()))
    G.PM.on = False
    settle()

    # THE RESOLVED GEOMETRY (tools/render_top_party.py: Trail + Hub on the geometry stub, every anchor resolved), every shape the renders
    # draw: bars one column wide (hub40), out of Party mode the left column, in it the grid under the HUD's bars with party1 under the HP
    # bar's left, the pet strip beside the HP bar ([me][pet]), the tab at the grid's left edge, no self row
    chk("hub40: every bar one column's bar (W_BAR %s = COL_W - BAR_X - 30)" % PF.W_BAR, PF.W_BAR == PF.COL_W - PF.BAR_X - 30, PF.W_BAR)
    import render_top_party as rtp
    col = PF.W_BAR
    for nm, sh in list(rtp.shapes().items()) + list(rtp.party_top_shapes().items()):
        rig = rtp.Rig()
        rig.scene(sh)
        k = rig.G.UIParent.hh / 1080.0
        bars = rig.party_bars()
        tb = rig.rect(rig.UF.tb.f.hp)
        P = rig.P
        hp, pw = rig.rect(P.hp), rig.rect(P.pw)
        bad = {u: round(v["hp"][2] - v["hp"][0], 2) for u, v in bars.items()
               if v["hp"][2] - v["hp"][0] > col * k + 1e-6 or abs((v["mp"][2] - v["mp"][0]) - (v["hp"][2] - v["hp"][0])) > 1e-6}
        chk("hub40: %s: %d bars, each <= one column (%.1f px), under the target bar's %.1f" % (nm, len(bars), col * k, tb[2] - tb[0]),
            len(bars) == sh["n"] - 1 and not bad and all(v["hp"][2] - v["hp"][0] < tb[2] - tb[0] for v in bars.values()), bad)
        L0 = rig.rect(P.petL) if sh["pet"] else None
        if not sh["mode"]:
            badp = {u: v["hp"][0] for u, v in bars.items() if abs(v["hp"][0] - (PF.X + PF.BAR_X) * k) > 1e-6
                    or abs(v["frame"][3] - (1080 - (PF.Y + (int(u[5:]) - 1) * PF.PITCH) * k)) > 1e-6}
            chk("hub42: %s: Party mode off: ONE column top left (x %.0f), party1..4 top to bottom" % (nm, (PF.X + PF.BAR_X) * k), not badp, badp)
            if L0:
                chk("hub42: %s: the pet strip stays under the resource bar (top %.1f under the power bar's %.1f)" % (nm, L0[3], pw[1]),
                    L0[3] < pw[1] and P.petSide == "under", (L0, pw))
            chk("hub42: %s: no tab out of Party mode, no aura room taken (%s)" % (nm, P.partyRoomPx),
                (rig.PF.tab is None or rig.PF.tab.shown is False) and P.partyRoomPx == 0, P.partyRoomPx)
        else:
            badg = {}
            gw = grid(len(bars))
            for u, v in bars.items():
                i = int(u[5:])
                wr, ws = gw[i]
                wx = hp[0] + (0 if ws == "left" else (PF.COL_W + PF.COL_GAP) * k)
                if abs(v["hp"][0] - wx) > 1e-6 or v["row"] != wr:
                    badg[u] = (v["hp"][0], wx, v["row"])
                if v["frame"][3] > pw[1] + 1e-6:
                    badg[u + " above"] = (v["frame"][3], pw[1])
            chk("hub42: %s: Party mode: the grid UNDER the HUD's bars (1 / 2 / 3 = [p1] + [p2][p3] / 4 = 2 x 2), party1's bar under the HP bar's left (%.1f)"
                % (nm, hp[0]), not badg, badg)
            if L0:
                chk("hub42: %s: [me][pet]: the pet strip RIGHT of the HP bar (its left %.1f past the HP bar's right %.1f), level with the bars"
                    % (nm, L0[0], hp[2]), L0[0] > hp[2] and L0[1] < hp[3] and L0[3] > pw[1] and P.petSide == "beside", (L0, hp, pw))
            t = rig.PF.tab
            tr = rig.rect(t)
            if bars:
                fr = [v["frame"] for v in bars.values()]
                left = min(x[0] for x in fr)
                chk("hub42: %s: the lit tab at the grid's LEFT edge (right %.1f < the first column's left %.1f), down the rows (%.1f..%.1f)"
                    % (nm, tr[2], left, tr[1], tr[3]),
                    t.shown is not False and rig.PF.tabLook == "on" and tr[2] < left and tr[2] > left - 20 * k
                    and tr[3] <= max(x[3] for x in fr) and tr[1] <= max(v["mp"][1] for v in bars.values()) and tr[0] > 0, (tr, fr))
                chk("hub42: %s: the grid takes its room under the bars (the aura row under it: %.1f spec px)" % (nm, P.partyRoomPx),
                    P.partyRoomPx > 0 and P.petDrop > P.partyRoomPx, (P.partyRoomPx, P.petDrop))
            chk("hub42 / prd15: %s: Party mode holds the HUD up (why %s)" % (nm, P.why), P.why in ("party", "fight") and P.shown is True, P.why)
            if sh.get("pick") == "pet":
                pa = rig.rect(P.pick.pet.area)
                chk("prd15: %s: the pick on your pet: the gold box round the strip BESIDE the HP bar (%.1f..%.1f)" % (nm, pa[0], pa[2]),
                    P.pickWho == "pet" and pa[0] < L0[0] + 1e-6 and pa[2] > L0[0] and pa[0] > hp[2], (pa, L0))
        chk("hub42: %s: no self row (no top frame for you / your pet)" % nm, rig.G.WowCompanionUFParty0 is None
            and rig.G.WowCompanionUFParty5 is None, "")
    # hub42 (the user's answer): solo in Party mode the tab stands beside the [me][pet] row, level with the HUD's bars
    rig = rtp.Rig()
    rig.scene(rtp.party_top_shapes()["mode_solo_pet"])
    hp, pw = rig.rect(rig.P.hp), rig.rect(rig.P.pw)
    tr = rig.rect(rig.PF.tab)
    chk("hub42: solo, Party mode: the lit tab LEFT of the HP bar (right %.1f < %.1f), spanning the two bars (%.1f..%.1f over %.1f..%.1f)"
        % (tr[2], hp[0], tr[1], tr[3], pw[1], hp[3]),
        rig.PF.tab.shown is not False and rig.PF.tabLook == "on" and tr[2] < hp[0] and tr[2] > hp[0] - 20 and tr[3] >= hp[3] and tr[1] <= pw[1]
        and rig.party_bars() == {}, tr)
    # prd15 (the user's answer): Party mode's buff / debuff row ABOVE the HUD bars, under them out of it; it fades with the strip
    for mode in (False, True):
        rig = rtp.Rig()
        rig.scene(rtp.party_top_shapes()["mode_pet_4members_buffs" if mode else "off_4members_buffs"])
        P = rig.P
        hp, pw = rig.rect(P.hp), rig.rect(P.pw)
        icons = [rig.rect(o.f) for o in list(P.buffs.values()) + list(P.debuffs.values()) if o.f.shown is not False]
        if mode:
            ok = icons and all(r[1] > hp[3] for r in icons) and P.auraUp is True
            fr = [v["frame"] for v in rig.party_bars().values()]
            ok = ok and all(r[1] > max(x[3] for x in fr) for r in icons)
        else:
            ok = icons and all(r[3] < pw[1] for r in icons) and P.auraUp is False
        chk("prd15: Party mode %s: %d aura icons %s the HUD bars (bars %.1f..%.1f; icons %s)"
            % ("on" if mode else "off", len(icons), "ABOVE" if mode else "under", pw[1], hp[3], [(round(r[1]), round(r[3])) for r in icons[:3]]),
            ok, icons)
    rig = rtp.Rig()
    rig.scene(rtp.party_top_shapes()["off_4members_buffs"])
    P = rig.P
    rig.G.PM.on = True
    seen = []
    for _ in range(30):
        rig.run(1 / 30)
        seen.append((P.auraUp, rig.G.RPLAIN(P.buffs[1].f.alpha) if P.buffs[1].f.alpha is not None else 1))
    chk("prd15: the aura row fades out under the bars, moves above, fades in (ends at alpha 1, above)",
        any((not up) and 0 < a < 1 for up, a in seen) and any(up and 0 < a < 1 for up, a in seen) and seen[-1] == (True, 1), seen[:14])
    # prd15 / hub42 THE ON-SCREEN GUARD (the user, 2026-11-12: "KEEP IT ON SCREEN"): in Party mode the block (the HUD's bars, the combo
    # pips, the rows and their rings) ends >= LIFT_MARGIN px from the screen's bottom - lifted by just enough, else not at all
    def lowest(rig):
        P = rig.P
        ys = [rig.rect(P.hp)[1], rig.rect(P.pw)[1]]
        if P.cp.shown is not False:
            ys.append(rig.rect(P.cp)[1])
        if P.petL.shown is not False:
            ys.append(rig.rect(P.petL)[1])
        for i in range(1, 5):
            f = rig.PF.frames[i]
            if f is not None and f.shown is not False:
                ys += [rig.rect(f.mp)[1], rig.rect(f.ring.area)[1]]
        return min(ys)
    for nm, lifted, why in (("mode_pet_4members", False, "4 members, the default height: fits (no lift)"),
                            ("mode_2members_nolift", False, "2 members: fits (no lift)"),
                            ("mode_rogue_4members_combo", True, "4 members + a Rogue's combo pips: lifted"),
                            ("mode_low_hud_4members", True, "4 members, the HUD set low (-420): lifted"),
                            ("off_rogue_4members_combo", False, "Party mode OFF (the pips, 4 members): nothing moves")):
        sh = rtp.party_top_shapes()[nm]
        rig = rtp.Rig()
        rig.scene(sh)
        P = rig.P
        lo = lowest(rig)
        m = P.LIFT_MARGIN
        y0 = sh.get("y", P.Y_DEF)
        hp_top = rig.rect(P.hp)[3]
        if lifted:
            ok = P.lift > 0 and m - 1e-6 <= lo <= m + 0.5 and abs(hp_top - (540 + y0 + P.lift)) < 1e-6
        else:
            ok = P.lift == 0 and (lo >= m - 1e-6 or not sh["mode"]) and abs(hp_top - (540 + y0)) < 1e-6
        chk("prd15 guard: %s - lift %.2f, the block's lowest edge %.1f px over the screen's bottom (margin %s), the HP bar's top %.1f"
            % (why, P.lift, lo, m, hp_top), ok, (P.lift, lo, hp_top))
        if lifted and sh["pet"]:
            L0, hp = rig.rect(P.petL), rig.rect(P.hp)
            chk("prd15 guard: %s - the pet strip lifted with the bars (beside the HP bar, %.1f..%.1f)" % (nm, L0[1], L0[3]),
                L0[0] > hp[2] and L0[1] < hp[3] and L0[3] > hp[1], (L0, hp))
    # the lift happens inside the fade: the HUD's bars and the rows change place only while (nearly) unseen
    rig = rtp.Rig()
    rig.scene(rtp.party_top_shapes()["off_rogue_4members_combo"])
    P = rig.P
    PL = rig.G.RPLAIN

    def watch(secs):
        out = []
        last = (P.lift, None)
        for _ in range(int(secs * 30)):
            rig.run(1 / 30)
            f1 = rig.PF.frames[1]
            ay = f1.anch[1][5] if f1.anch else None
            out.append((P.lift, PL(P.f.alpha) or 0, ay, rig.PF.fa, last))
            last = (P.lift, ay)
        return out

    rig.G.PM.on = True
    seen = watch(1.2)
    jumps = [(x[0], x[1]) for x in seen if x[4][0] != x[0] and x[1] > 0.05]
    rjump = [(x[2], x[3]) for x in seen if x[4][1] is not None and x[4][1] != x[2] and x[3] > 0.05]
    chk("prd15 guard: Party mode on (a Rogue, 4 members): lifted %.2f, the bars moved only while faded (%s), the rows only at alpha 0 (%s), "
        "the HUD back at its alpha (%.2f)" % (P.lift, jumps, rjump, seen[-1][1]),
        P.lift > 0 and not jumps and not rjump and abs(seen[-1][1] - 0.92) < 1e-6 and any(x[1] < 0.05 for x in seen), seen[:10])
    # the combo pips go (a druid leaving cat form; here the class changes): the guard re-reads, re-placed inside a fade again
    rig.G.PLAYERCLS = "SHAMAN"
    seen = watch(1.2)
    jumps = [(x[0], x[1]) for x in seen if x[4][0] != x[0] and x[1] > 0.05]
    rjump = [(x[2], x[3]) for x in seen if x[4][1] is not None and x[4][1] != x[2] and x[3] > 0.05]
    chk("prd15 guard: the combo pips gone: no lift needed now (%.2f), moved down inside the fade (bars %s, rows %s)" % (P.lift, jumps, rjump),
        P.lift == 0 and P.cp.shown is False and not jumps and not rjump and abs(seen[-1][1] - 0.92) < 1e-6, seen[:10])
    rig.G.PLAYERCLS = "ROGUE"
    watch(1.2)
    rig.G.PM.on = False
    seen = watch(1.2)
    jumps = [(x[0], x[1]) for x in seen if x[4][0] != x[0] and x[1] > 0.05]
    chk("prd15 guard: Party mode off: the lift gone (%.2f), the bars back down inside the fade (%s), the rows back in the left column"
        % (P.lift, jumps), P.lift == 0 and not jumps and rig.PF.at == "left" and abs(seen[-1][1] - 0.92) < 1e-6, seen[:10])
    # Party mode on and off in the rig (every frame driven): the pet strip fades out, moves beside the HP bar, fades in; and back
    rig = rtp.Rig()
    rig.scene(rtp.party_top_shapes()["off_4members"])
    P = rig.P
    rig.G.PM.on = True
    seen = []
    for _ in range(30):
        rig.run(1 / 30)
        seen.append((P.petSide, P.petFadeA, rig.PF.at, rig.PF.fa))
    chk("prd15: Party mode on: the pet strip fades out under the bars, moves, fades in beside them (alpha ramps both ways, ends at 1)",
        any(x[0] == "under" and x[1] is not None and 0 < x[1] < 1 for x in seen)
        and any(x[0] == "beside" and x[1] is not None and 0 < x[1] < 1 for x in seen)
        and seen[-1][0] == "beside" and seen[-1][1] is None, seen[:12])
    chk("hub42: ... the block too: out of the column, in under the HUD (ends at alpha 1, under the HUD)",
        any(x[2] == "left" and 0 < x[3] < 1 for x in seen) and any(x[2] == "hud" and 0 < x[3] < 1 for x in seen)
        and seen[-1][2] == "hud" and seen[-1][3] == 1, [x[2:] for x in seen[:14]])
    rig.G.PM.on = False
    rig.run(1.0)
    chk("prd15 / hub42: Party mode off again: the strip back under the bars, the block back in the left column, no room held",
        P.petSide == "under" and rig.PF.at == "left" and P.partyRoomPx == 0 and P.petFadeA is None and rig.PF.fa == 1,
        (P.petSide, rig.PF.at, P.partyRoomPx))
    # a row made at one scale, the scale recomputed at PLAYER_ENTERING_WORLD (Hub's K = UIParent:GetHeight() / 1080): re-sized
    rig = rtp.Rig()
    rig.scene(rtp.shapes()["party3"])
    w0 = {u: v["hp"][2] - v["hp"][0] for u, v in rig.party_bars().items()}
    rig.set_k(768)
    b1 = rig.party_bars()
    w1 = {u: v["hp"][2] - v["hp"][0] for u, v in b1.items()}
    chk("hub40: after a K change (1080 -> 768): every bar re-sized to the new scale's column (%s -> %s), the heights too" % (w0, w1),
        all(abs(w - col) < 1e-6 for w in w0.values()) and all(abs(w - col * 768 / 1080) < 1e-6 for w in w1.values())
        and all(abs((v["hp"][3] - v["hp"][1]) - PF.HP * 768 / 1080) < 1e-6 for v in b1.values()), (w0, w1))
    # hub41 / hub42: the Select tab follows the K change too - under the HUD, its top level with the first row's name (NAME_H - 4 under
    # the first row's top at today's K), its width TAB_W * K, its cap slices TAB_W * K * the art's cap ratio
    rig = rtp.Rig()
    rig.scene(rtp.shapes()["party3_mode"])
    rig.set_k(768)
    b1 = rig.party_bars()
    t = rig.PF.tab
    tr = rig.rect(t)
    k2 = 768 / 1080.0
    top0 = max(v["frame"][3] for v in b1.values())
    capw = PF.TAB_W * k2 * 120 * PF.TAB_CAP / 110
    chk("hub41: after the K change the Select tab moved with the block (top %.2f = the first row's top %.2f - (NAME_H - 4) * K, width %.2f)"
        % (tr[3], top0, tr[2] - tr[0]),
        abs(tr[3] - (top0 - (PF.NAME_H - 4) * k2)) < 1e-6 and abs((tr[2] - tr[0]) - PF.TAB_W * k2) < 1e-6
        and abs(t.base[1].h - capw) < 1e-6 and abs(t.lit[3].h - capw) < 1e-6
        and abs(t.sel.w - (PF.TAB_W - 2) * k2) < 1e-6, (tr, top0, t.base[1].h, capw))
    # hub41: Party mode's gold ring never crosses another row's name, every mode shape, both K
    for nm, sh in rtp.shapes().items():
        if not sh["mode"]:
            continue
        for uih in (None, 768):
            rig = rtp.Rig()
            rig.scene(dict(sh, uih=uih) if uih else sh)
            hit = []
            for i in range(1, 5):
                f = rig.PF.frames[i]
                if f is None or f.shown is False:
                    continue
                a = rig.rect(f.ring.area)
                for j in range(1, 5):
                    g = rig.PF.frames[j]
                    if j == i or g is None or g.shown is False:
                        continue
                    n = rig.rect(g.name)
                    if a[0] < n[2] and n[0] < a[2] and a[1] < n[3] and n[1] < a[3]:
                        hit.append((i, j))
            chk("hub41: %s (K %s): no row's ring over another row's name" % (nm, uih or 1080), not hit, hit)
    # the OLD code (1428, hub37) under the same rig: one member alone got the two-column bar - the user's "mile"
    import subprocess
    try:
        old = subprocess.run(["git", "-C", rtp.ROOT, "show", "097503d8:" + rtp.HUB_REL], capture_output=True, check=True).stdout.decode("utf-8")
    except Exception:
        old = None
    if old:
        rig = rtp.Rig(old)
        rig.scene(rtp.shapes()["party2"])
        wo = {u: round(v["hp"][2] - v["hp"][0]) for u, v in rig.party_bars().items()}
        chk("hub40: the 1428 code's lone member had the 510 px bar (the bug this fixes): %s" % wo, wo == {"party1": 510}, wo)
    # uf15 / at8 (the user, 2026-11-12: "the target bars cast bar is too squished into it", "the aggro tint looks like its floating"):
    # the pips / cast bar start clear of the HUD rim's visible lower lip (4 texels, k = 3 px at 1080) plus a gap; the tint round the
    # health bar only, its outline on the rim's visible edge (3 texels out) on every side
    import render_target_bar as rtb
    _, tint, hp, cast, cp, ufv, atv = rtb.shoot(None, None, dict(tint=True, cast=True, pips=True))
    k = (hp[3] - hp[1]) / 8
    chk("uf15: UF.V uf15, at8: rings V at8 (%s / %s)" % (ufv, atv), ufv == "uf15" and atv == "at8")
    chk("uf15: the pips' top %.1f px under the bar (>= the lip %.0f + 4), the cast below them %.1f" % (hp[1] - cp[3], 4 * k, cp[1] - cast[3]),
        hp[1] - cp[3] >= 4 * k + 4 and cp[1] - cast[3] >= 4)
    _, _, hp2, cast2, _, _, _ = rtb.shoot(None, None, dict(cast=True))
    chk("uf15: no pips: the cast's top %.1f px under the bar (>= the lip %.0f + 4)" % (hp2[1] - cast2[3], 4 * k), hp2[1] - cast2[3] >= 4 * k + 4)
    W0, H0 = 184.0, 38.0
    sx, sy = (tint[2] - tint[0]) / W0, (tint[3] - tint[1]) / H0
    out = (hp[0] - tint[0] - 6 * sx, tint[3] - hp[3] - 5 * sy, tint[2] - hp[2] - 6 * sx, hp[1] - tint[1] - 6 * sy)
    chk("at8: the tint's outline 3 texels (%.1f px) out of the health bar on every side (l t r b %s), never round the cast bar"
        % (3 * k, ["%.2f" % v for v in out]), all(abs(v - 3 * k) < 0.05 for v in out) and tint[1] > cast[3])
    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
