"""The TOP screen's party block (WowCompanionHub/Hub.lua hub35; party_design.md D, E, the user's decisions and ADAPTIVE ROWS 2026-11-11), off-device:
Hub.lua under a stub game whose frames remember what was drawn, PadMap's exports stubbed (WC.padMap.party: on, box, lb, rng, selWant).

    python tools/test_party_top.py

Checks the block (hub35: only party1..4, in the bottom's arrangement of PadMap m131's entries - the same rows and sides, the you / pet
rows dropped, a full row's bar both columns wide; no self row; a raid or our switch off: nothing),
Party mode's looks (each row's LB glyph, the gold ring on the pick box.u, the white chevron at your target; off: a white ring on your
target), dead / offline / out of range (the shared rng: 0.45), the HUD green re-tinted every step, the secret health only into the
widget setters, and the Select tab (off / on / not bound; hub38: the game's side tab in three slices on the screen's left edge,
lit by its selected art - the user, 2026-11-11). hub38: the rows follow PadMap m134 (you + the pet share the first row). Needs lupa. Exit code 1 on any failure."""
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
    chk("hub38: the version tag (the player / pet row, the game's side tab)", 'local HV = "hub38"' in src)
    chk("hub34: the combat log's switch calls are gone (PadMap m129)", 'WC.clogSync' not in src and 'WC.clogTicker' not in src)

    # solo, Party mode off: nothing (no row, no tab)
    G.WowCompanion.partyOk = True
    step()
    chk("solo, Party mode off: no row, no tab", frames() == {} and PF.rows == 0 and (PF.tab is None or not PF.tab.shown), PF.rows)
    # hub35 (the user, 2026-11-11: "When selecting myself or my pet it just highlights my HUD bar"): NO self row; solo with Party mode on
    # only the tab (one row high) says it is on
    G.PM.on = True
    G.PM.u = "player"
    step()
    chk("hub35: solo, Party mode on: no row at all (you = the HUD's bars), the Select tab on", frames() == {} and PF.rows == 0
        and PF.tab is not None and PF.tab.shown and PF.tabLook == "on", (list(frames()), PF.tabLook))
    chk("hub35: no frame is ever made for you (no WowCompanionUFParty0)", G.WowCompanionUFParty0 is None, "")
    # a party of two (you, party1, party2: n 3, one full row each): party1 then party2, full width
    L.execute('UNITS.party1 = { name = "Brannoc", cls = "WARRIOR", lv = 21 }; UNITS.party2 = { name = "Iselle", cls = "MAGE", lv = 19 }; '
              'PM.on = false; PM.u = "party1"; TARGET = "party2"')
    step()
    f = frames()
    chk("hub35: grouped (n 3), Party mode off: party1 row 0, party2 row 1, both full width (you are not drawn)",
        where(f) == {1: (0, "full"), 2: (1, "full")} and f[1].look.w == PF.W_FULL, where(f))
    chk("Party mode off: no LB glyph (the slot kept empty), a WHITE ring on your target (party2), no gold ring",
        all(f[i].look.glyph is None for i in f) and f[2].look.ring == "white" and f[1].look.ring is None
        and not any(f[i].look.chev for i in f), [(f[i].look.ring, f[i].look.glyph) for i in sorted(f)])
    chk("names in class colour, the level in its box", f[1].name.text == "Brannoc" and abs(f[1].name.tc[1] - 0.78) < 1e-6 and f[1].lvl.text == 21,
        (f[1].name.text, f[1].lvl.text))
    chk("hub35: the tab two rows high beside the block", PF.tab.shown and PF.tabLook == "off", PF.tabLook)
    # Party mode on: glyphs, the gold ring on the pick, the white chevron at your target
    G.PM.on = True
    step()
    f = frames()
    chk("Party mode on: each row's LB glyph (p1, p2)", [f[i].look.glyph for i in sorted(f)] == ["PADDUP", "PADDDOWN"],
        [f[i].look.glyph for i in sorted(f)])
    chk("Party mode on: the GOLD ring on the pick (party1), none on your target; the white chevron at your target (party2)",
        f[1].look.ring == "gold" and f[2].look.ring is None and f[2].look.chev is True and f[1].look.chev is False,
        [(f[i].look.ring, f[i].look.chev) for i in sorted(f)])
    chk("the ring's gold is the bottom box's (1, 0.82, 0)", f[1].ring.t[1].ct[0 + 1] == 1 and abs(f[1].ring.t[1].ct[2] - 0.82) < 1e-6, "")
    # hub35 review (2026-11-11): the ring on ONE area (TOPLEFT on the health bar, BOTTOMRIGHT on the power bar), every edge on it alone
    rg = L.eval('''(function()
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
    end)()''')
    chk("hub35 review: the ring on one area (health's top-left to power's bottom-right), every edge on that area alone", rg == "ok", rg)
    G.PM.u = "player"
    step()
    f = frames()
    chk("hub35: the pick on you: no ring on any party row (the HUD's bars wear it)", all(f[i].look.ring is None for i in f),
        [f[i].look.ring for i in sorted(f)])
    G.PM.u = "party1"
    # hub35 THE BOTTOM'S ARRANGEMENT (PadMap m131's entries, PM.units = WC.padMap.party.units): every shape the top can hold
    L.execute('UNITS.party3 = { name = "Doran", cls = "WARRIOR", lv = 20 }; UNITS.party4 = { name = "Mira", cls = "MAGE", lv = 20 }')
    shapes = [
        (["player", "party1", "party2", "party3", "party4"], {1: (0, "left"), 2: (0, "right"), 3: (1, "left"), 4: (1, "right")},
         "n 5, no pet: you | p1 p2 | p3 p4 -> the 2x2 square"),
        (["player", "pet", "party1", "party2", "party3", "party4"], {1: (0, "left"), 2: (0, "right"), 3: (1, "left"), 4: (1, "right")},
         "n 6: you pet | p1 p2 | p3 p4 -> the 2x2 square (the you / pet row dropped)"),
        (["player", "pet", "party1", "party2", "party3"], {1: (0, "full"), 2: (1, "left"), 3: (1, "right")},
         "hub38: n 5 with the pet: you pet | p1 | p2 p3 -> p1 full, p2 p3 under it (as without the pet)"),
        (["player", "party1", "party2", "party3"], {1: (0, "full"), 2: (1, "left"), 3: (1, "right")},
         "n 4, no pet: you | p1 | p2 p3 -> p1 full, p2 p3 under it"),
        (["player", "pet", "party1", "party2"], {1: (0, "full"), 2: (1, "full")}, "hub38: n 4 with the pet: you pet | p1 | p2 -> two full rows"),
        (["player", "pet", "party1"], {1: (0, "full")}, "n 3 with the pet: you pet | p1 -> p1 full"),
        (["player", "party1", "party2"], {1: (0, "full"), 2: (1, "full")}, "n 3, no pet: you | p1 | p2 -> two full rows"),
    ]
    for units, want, why in shapes:
        G.PM.units = L.table_from(units)
        step()
        f = frames()
        rows = max(r for r, _ in want.values()) + 1
        chk("hub35: " + why, where(f) == want and PF.rows == rows, (where(f), PF.rows))
        chk("hub35: ... widths: a half %d, a full %d; the right column %d px over" % (PF.W_HALF, PF.W_FULL, PF.COL_W + PF.COL_GAP),
            all(f[i].look.w == (PF.W_FULL if s_ == "full" else PF.W_HALF) for i, (_, s_) in want.items()), [f[i].look.w for i in f])
    G.PM.units = None
    L.execute('UNITS.party3 = nil; UNITS.party4 = nil')
    step()
    f = frames()
    chk("hub35: back to two members (PadMap's list gone: the plain count): party1, party2 full rows again",
        where(f) == {1: (0, "full"), 2: (1, "full")} and not (PF.frames[3] and PF.frames[3].shown), where(f))
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
    L.execute('PM.rng = {}')
    # the HUD's green, set every step; secret health only into the setters
    p0 = f[1].hp.paints or 0
    step()
    step()
    f = frames()
    chk("the health re-tinted every step (PRD.HEALTH_RGB's green)", (f[1].hp.paints or 0) >= p0 + 2 and abs(f[1].hp.sbc[2] - 0.86) < 1e-6,
        (p0, f[1].hp.paints))
    chk("the secret health went into the bar's setters only", G.rawequal(f[1].hp.val, G.SECRET) and G.rawequal(f[1].hp.mm[2], G.SECRET), "")
    # the top Select tab: on / off / not bound
    tab = PF.tab
    def shown(parts):
        return [p.shown for p in parts.values()]
    chk("hub38: the Select tab: on (Party mode on), the B glyph shown, the game's side tab lit (its selected art shown over it)",
        tab is not None and tab.shown and PF.tabLook == "on" and tab.b.shown and all(shown(tab.base)) and all(shown(tab.lit))
        and len(tab.base) == 3 and len(tab.lit) == 3, PF.tabLook)
    chk("hub38: the side tab is the game's atlas in three slices (no drawn fill / edge), flush on the screen's left edge",
        all(p.tex == 7000001 for p in tab.base.values()) and G.rawget(tab, "bg") is None and G.rawget(tab, "edge") is None and PF.TAB_X == 0
        and tab.anch[1][4] == 0 and abs(tab.base[1].tc[3] - 0.5) < 1e-9 and abs(tab.base[3].tc[4] - 0.7) < 1e-9
        and abs(tab.base[1].h - PF.TAB_W * 120 * PF.TAB_CAP / 110) < 1e-6,
        (tab.anch[1][4], list(tab.base[1].tc.values()), list(tab.base[3].tc.values()), tab.base[1].h))
    G.PM.on = False
    step()
    chk("hub38: off, Select bound: the plain side tab (its lit art hidden), no B, no lock", PF.tabLook == "off" and all(shown(tab.base))
        and not any(shown(tab.lit)) and not tab.b.shown and not tab.lock.shown, PF.tabLook)
    G.PM.sel = "dialog"
    step()
    chk("Select not bound (a dialog): the glyph at 40 %, the lock, the side tab dimmer", PF.tabLook == "unbound" and tab.sel.alpha == 0.4
        and tab.lock.shown and tab.base[1].alpha == 0.6 and not any(shown(tab.lit)), PF.tabLook)
    G.PM.sel = "bound"
    G.WowCompanion.partyOk = False
    step()
    chk("Party mode not allowed (the experimental switch off): no tab, the column stays", not tab.shown and len(frames()) == 2, tab.shown)
    G.WowCompanion.partyOk = True
    # a raid: nothing; our switch off: nothing
    G.RAID = True
    step()
    chk("in a raid: nothing (no row, no tab)", frames() == {} and not tab.shown, list(frames()))
    G.RAID = False
    UF.party = False
    step()
    chk("our party frames switched off (SW_UFP): nothing", frames() == {} and not tab.shown, list(frames()))
    UF.party = True
    step()
    chk("on again: two rows", len(frames()) == 2, len(frames()))
    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
