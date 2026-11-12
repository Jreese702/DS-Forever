"""The TOP screen's party column (WowCompanionHub/Hub.lua hub34; party_design.md D, E and the user's decisions 2026-11-11), off-device:
Hub.lua under a stub game whose frames remember what was drawn, PadMap's exports stubbed (WC.padMap.party: on, box, lb, rng, selWant).

    python tools/test_party_top.py

Checks the rows (you first, then party1..4; your own row while grouped or whenever Party mode is on; a raid or our switch off: nothing),
Party mode's looks (each row's LB glyph, the gold ring on the pick box.u, the white chevron at your target; off: a white ring on your
target), dead / offline / out of range (the shared rng: 0.45), the HUD green re-tinted every step, the secret health only into the
widget setters, and the 18 px Select tab (off / on / not bound). Needs lupa. Exit code 1 on any failure."""
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
function Frame:SetStatusBarColor(r, g, b) self.sbc = { r, g, b }; self.paints = (rawget(self, "paints") or 0) + 1 end
function Frame:SetStatusBarTexture(t) self.sbt = t end
function Frame:GetStatusBarTexture() return self.sbt end
function Frame:SetMinMaxValues(a, b) self.mm = { a, b } end
function Frame:SetValue(v) self.val = v end
function Frame:SetAtlas(a) if not ATLAS[a] then error("no atlas " .. tostring(a)) end; self.atlas = a end
function Frame:SetTexture(t) self.tex = t end
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
        for i in range(5):
            f = PF.frames[i]
            if f is not None and f.shown:
                out[i] = f
        return out

    src = open(HUB, encoding="utf-8").read()
    chk("hub34: the version tag (hub36 since Polestar's hide sticks)", 'local HV = "hub36"' in src)
    chk("hub34: the combat log's switch calls are gone (PadMap m129)", 'WC.clogSync' not in src and 'WC.clogTicker' not in src)

    # solo, Party mode off: nothing (no self row, no tab)
    G.WowCompanion.partyOk = True
    step()
    chk("solo, Party mode off: no row, no tab", frames() == {} and PF.rows == 0 and (PF.tab is None or not PF.tab.shown), PF.rows)
    # solo, Party mode on: your own row only (the user, 2026-11-11: "shown while grouped or whenever party mode is on")
    G.PM.on = True
    G.PM.u = "player"
    step()
    f = frames()
    chk("solo, Party mode on: your own row only (row 0 = player), the gold ring on you (box.u)",
        list(f) == [0] and f[0].unit == "player" and f[0].look.ring == "gold" and f[0].name.text == "Korvash", list(f))
    chk("... the LB glyph in your slot (lb.self)", f[0].look.glyph == "PAD1", f[0].look.glyph)
    # a party of three, Party mode off: you first, then party1, party2 - one row each
    L.execute('UNITS.party1 = { name = "Brannoc", cls = "WARRIOR", lv = 21 }; UNITS.party2 = { name = "Iselle", cls = "MAGE", lv = 19 }; '
              'PM.on = false; PM.u = "party1"; TARGET = "party2"')
    step()
    f = frames()
    chk("grouped, Party mode off: rows you, party1, party2 (the bottom's order)", [f[i].unit for i in sorted(f)] == ["player", "party1", "party2"],
        [f[i].unit for i in sorted(f)])
    chk("Party mode off: no LB glyph (the slot kept empty), a WHITE ring on your target (party2), no gold ring",
        all(f[i].look.glyph is None for i in f) and f[2].look.ring == "white" and f[1].look.ring is None and f[0].look.ring is None
        and not any(f[i].look.chev for i in f), [(f[i].look.ring, f[i].look.glyph) for i in sorted(f)])
    chk("names in class colour, the level in its box", f[1].name.text == "Brannoc" and abs(f[1].name.tc[1] - 0.78) < 1e-6 and f[1].lvl.text == 21,
        (f[1].name.text, f[1].lvl.text))
    # Party mode on: glyphs, the gold ring on the pick, the white chevron at your target
    G.PM.on = True
    step()
    f = frames()
    chk("Party mode on: each row's LB glyph (self, p1, p2)", [f[i].look.glyph for i in sorted(f)] == ["PAD1", "PADDUP", "PADDDOWN"],
        [f[i].look.glyph for i in sorted(f)])
    chk("Party mode on: the GOLD ring on the pick (party1), none on your target; the white chevron at your target (party2)",
        f[1].look.ring == "gold" and f[2].look.ring is None and f[2].look.chev is True and f[1].look.chev is False,
        [(f[i].look.ring, f[i].look.chev) for i in sorted(f)])
    chk("the ring's gold is the bottom box's (1, 0.82, 0)", f[1].ring.t[1].ct[0 + 1] == 1 and abs(f[1].ring.t[1].ct[2] - 0.82) < 1e-6, "")
    # dead, offline, out of range (PadMap's rng)
    L.execute('UNITS.party1.dead = true; UNITS.party2.off = true')
    step()
    f = frames()
    chk("dead: the grey ground and the skull in the level box (the level hidden)", f[1].look.dead is True and f[1].deadG.shown and f[1].skull.shown
        and not f[1].lvl.shown, (f[1].deadG.shown, f[1].skull.shown))
    chk("offline: the row at 0.45; alive and in range: 1", f[2].alpha == 0.45 and f[0].alpha == 1 and f[1].alpha == 1, (f[2].alpha, f[1].alpha))
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
    chk("the Select tab beside the column: on (Party mode on), the B glyph shown, gold fill at 35 %",
        tab is not None and tab.shown and PF.tabLook == "on" and abs(tab.bg.ct[4] - 0.35) < 1e-6, PF.tabLook)
    G.PM.on = False
    step()
    chk("off, Select bound: the dark panel (rgb 20 17 13), no lock", PF.tabLook == "off" and abs(tab.bg.ct[1] - 20 / 255) < 1e-6 and not tab.lock.shown,
        PF.tabLook)
    G.PM.sel = "dialog"
    step()
    chk("Select not bound (a dialog): the glyph at 40 %, the lock", PF.tabLook == "unbound" and tab.sel.alpha == 0.4 and tab.lock.shown, PF.tabLook)
    G.PM.sel = "bound"
    G.WowCompanion.partyOk = False
    step()
    chk("Party mode not allowed (the experimental switch off): no tab, the column stays", not tab.shown and len(frames()) == 3, tab.shown)
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
    chk("on again: three rows", len(frames()) == 3, len(frames()))
    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
