"""HUD mode's own switches (WowCompanionHub/Hub.lua hub17, the row "uisw"; Trail.lua's consumers), off-device: Hub.lua under a stub
game, Trail.lua under the compass tests' stub for the nameplate and personal-bar wishes.

    python tools/test_hud_switches.py

Checks each switch alone (the player frame with the pet's, the target frame with its target's, the enemy nameplates, the personal bar),
absolute taps (a repeat changes nothing), alpha only (never Show / Hide), the bundle's old columns 34 / 35 still moving every part, a
switch once set winning over the bundle, the hub document's sw (the row, the columns, the state), and the app's defaults applied as
taps (the frames hidden, the nameplates and the bar on). Needs lupa. Exit code 1 on any failure."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import test_compass as tc  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HUB = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons", "WowCompanionHub", "Hub.lua")

STUB = r'''
SENT, ROWS, CALLS = {}, {}, { show = 0, hide = 0 }
local Frame = {}
local ABSENT = { UpdateSystemSettingOpacity = true, ApplyAlpha = true, OnEvent = true, OnUpdate = true }
local function noop() end
Frame.__index = function(t, k) local v = rawget(Frame, k); if v ~= nil then return v end; if ABSENT[k] then return nil end; return noop end
function Frame:SetAlpha(a) self.alpha = a end
function Frame:GetAlpha() return self.alpha or 1 end
function Frame:Show() CALLS.show = CALLS.show + 1 end
function Frame:Hide() CALLS.hide = CALLS.hide + 1 end
function Frame:IsShown() return true end
function Frame:HookScript() end
function Frame:RegisterEvent() return true end
function Frame:SetScript(k, fn) self[k] = fn end
function CreateFrame() return setmetatable({}, Frame) end
for _, n in ipairs({ "PlayerFrame", "PetFrame", "TargetFrame", "TargetFrameToT" }) do _G[n] = setmetatable({ alpha = 1 }, Frame) end
function hooksecurefunc() end
SlashCmdList = {}
function InCombatLockdown() return false end
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

    # Hub.lua under the stub, its frames caught as made, then the first event (its setup registers the rows)
    L2 = tc.lupa.LuaRuntime()
    L2.execute(STUB + r'''
        MADE = {}
        local base = CreateFrame
        function CreateFrame(...) local f = base(...); MADE[#MADE + 1] = f; return f end
    ''')
    L2.execute(open(HUB, encoding="utf-8").read().replace("local ADDON = ...", "local ADDON = 'WowCompanionHub'", 1))
    G = L2.globals()
    for i in range(1, len(G.MADE) + 1):
        f = G.MADE[i]
        if f.OnEvent:
            f.OnEvent(f, "PLAYER_LOGIN")
    hub = G.WowCompanion.hub
    sw = G.ROWS.uisw
    ui = G.ROWS.ui
    chk("hub17: the switches' own row 'uisw' registered beside the full 'ui' row", sw is not None and ui is not None and hub.swRow is not None)

    def a(n):
        return G[n].alpha

    def doc():
        G.SENT = L2.table()
        sw(None, 99)                                      # (an unknown column: nothing changes, the document goes out)
        lst = [G.SENT[i] for i in range(1, len(G.SENT) + 1) if G.SENT[i].type == "hub"]
        return lst[-1].hub if lst else None
    d0 = doc()
    chk("before any tap: every frame shown, nothing wanted (%s / %s, np %s, prd %s)" % (d0.sw.player, d0.sw.target, d0.sw.np, d0.sw.prd),
        d0.sw.player == "shown" and d0.sw.target == "shown" and d0.sw.np is False and d0.sw.prd is False and d0.sw.row == "uisw"
        and a("PlayerFrame") == 1 and a("TargetFrame") == 1)
    SW = hub.SW
    # THE APP'S DEFAULTS (the user: the player / target frames OFF, the enemy nameplates ON, the personal bar ON): four taps
    for c in (SW.PLAYER_HIDE, SW.TARGET_HIDE, SW.NP_ON, SW.PRD_ON):
        sw(None, c)
    d1 = doc()
    chk("the defaults as taps: the player and pet frames hidden (%s, %s), the target and its target hidden (%s, %s)"
        % (a("PlayerFrame"), a("PetFrame"), a("TargetFrame"), a("TargetFrameToT")),
        a("PlayerFrame") == 0 and a("PetFrame") == 0 and a("TargetFrame") == 0 and a("TargetFrameToT") == 0
        and d1.sw.player == "hidden" and d1.sw.target == "hidden")
    chk("... the nameplates and the personal bar wanted (np %s, prd %s)" % (G.WowCompanion.npWant.np, G.WowCompanion.npWant.prd),
        G.WowCompanion.npWant.np is True and G.WowCompanion.npWant.prd is True and d1.sw.np is True and d1.sw.prd is True)
    # EACH ALONE
    sw(None, SW.PLAYER_SHOW)
    chk("the player frame alone: shown (%s, pet %s), the target still hidden (%s)" % (a("PlayerFrame"), a("PetFrame"), a("TargetFrame")),
        a("PlayerFrame") == 1 and a("PetFrame") == 1 and a("TargetFrame") == 0 and a("TargetFrameToT") == 0)
    sw(None, SW.TARGET_SHOW); sw(None, SW.PLAYER_HIDE)
    chk("the target frame alone: shown (%s, its target %s), the player hidden again (%s)" % (a("TargetFrame"), a("TargetFrameToT"), a("PlayerFrame")),
        a("TargetFrame") == 1 and a("TargetFrameToT") == 1 and a("PlayerFrame") == 0 and a("PetFrame") == 0)
    sw(None, SW.NP_OFF)
    chk("the nameplates alone off (np %s), the bar still on (%s)" % (G.WowCompanion.npWant.np, G.WowCompanion.npWant.prd),
        G.WowCompanion.npWant.np is False and G.WowCompanion.npWant.prd is True)
    sw(None, SW.PRD_OFF); sw(None, SW.NP_ON)
    chk("the bar alone off (prd %s), the nameplates on again (%s)" % (G.WowCompanion.npWant.prd, G.WowCompanion.npWant.np),
        G.WowCompanion.npWant.prd is False and G.WowCompanion.npWant.np is True)
    sw(None, SW.NP_ON); sw(None, SW.NP_ON)
    chk("absolute: a repeated tap changes nothing", G.WowCompanion.npWant.np is True)
    dw0 = doc()
    sw(None, SW.WM_ON)
    dw1 = doc()
    sw(None, SW.WM_OFF)
    dw2 = doc()
    chk("hub18: the world markers' switch: off before any tap (%s), 9 on (%s, wm %s), 8 off (%s); the doc's cols name them"
        % (dw0.sw.wm, dw1.sw.wm, G.WowCompanion.npWant.wm, dw2.sw.wm),
        dw0.sw.wm is False and dw1.sw.wm is True and dw2.sw.wm is False and dw2.sw.set.wm is True
        and dw2.sw.cols.WM_OFF == 8 and dw2.sw.cols.WM_ON == 9)
    chk("alpha only: no Show / Hide on any game frame (%d / %d)" % (G.CALLS.show, G.CALLS.hide), G.CALLS.show == 0 and G.CALLS.hide == 0)
    # THE BUNDLE (an older app's columns 34 / 35): moves every part a switch never set; a set switch wins
    L3 = tc.lupa.LuaRuntime()
    L3.execute(STUB + "MADE = {}; local base = CreateFrame; function CreateFrame(...) local f = base(...); MADE[#MADE + 1] = f; return f end")
    L3.execute(open(HUB, encoding="utf-8").read().replace("local ADDON = ...", "local ADDON = 'WowCompanionHub'", 1))
    G3 = L3.globals()
    for i in range(1, len(G3.MADE) + 1):
        if G3.MADE[i].OnEvent:
            G3.MADE[i].OnEvent(G3.MADE[i], "PLAYER_LOGIN")
    G3.ROWS.ui(None, 35)
    chk("an older app's HUD mode (column 35): every frame hidden, the nameplates and the bar wanted (as hub16)",
        G3.PlayerFrame.alpha == 0 and G3.TargetFrame.alpha == 0 and G3.WowCompanion.npWant.hud is True)
    G3.ROWS.uisw(None, G3.WowCompanion.hub.SW.TARGET_SHOW)
    G3.ROWS.ui(None, 35)
    chk("a switch once set wins over the bundle: the target shown (%s) while HUD mode hides the player (%s)"
        % (G3.TargetFrame.alpha, G3.PlayerFrame.alpha), G3.TargetFrame.alpha == 1 and G3.PlayerFrame.alpha == 0)
    # TRAIL.LUA's consumers: the nameplate wish and the personal bar follow their own switches, else the bundle
    LT = tc.run()
    T = LT.globals()
    A, P = T.WowCompanion.live, T.WowCompanion.prd
    src = open(tc.TRAIL, encoding="utf-8").read()
    chk("Trail.lua reads the nameplates' own switch (npWant.np) before the bundle, and the bar's (npWant.prd)",
        "npw.np" in src and "npw.prd" in src)
    T.WowCompanion.npWant = LT.table_from({"hud": True, "prd": False})
    ok, why = P.wanted()
    chk("the bar's own switch off wins over HUD mode on (%s, %s)" % (ok, why), ok is False and why == "hud off")
    T.WowCompanion.npWant = LT.table_from({"prd": True})
    r = P.wanted()
    ok = r[0] if isinstance(r, tuple) else r
    chk("the bar's own switch on with no HUD mode: wanted (%s)" % ok, ok is not False)
    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
