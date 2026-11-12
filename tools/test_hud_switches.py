"""HUD mode's own switches (WowCompanionHub/Hub.lua hub17, the row "uisw"; Trail.lua's consumers), off-device: Hub.lua under a stub
game, Trail.lua under the compass tests' stub for the nameplate and personal-bar wishes.

    python tools/test_hud_switches.py

Checks each switch alone (the player frame with the pet's, the target frame with its target's, the enemy nameplates, the personal bar;
hub33: the pet's strip, and the game's pet frame as its own group - as the player frame, never hidden by our strip alone),
absolute taps (a repeat changes nothing), alpha only (never Show / Hide), the bundle's old columns 34 / 35 still moving every part, a
switch once set winning over the bundle, the hub document's sw (the row, the columns, the state), and the app's defaults applied as
taps (the frames hidden, the nameplates and the bar on); hub31 / hub36: Polestar's windows kept at alpha 0 through its own
SetAlpha, fades, re-shows, later-made and re-parented frames, and given back at Off. Needs lupa. Exit code 1 on any failure."""
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
    chk("the defaults as taps (no Trail.lua here: no pet strip): the player and pet frames hidden (%s, %s), the target and its target hidden (%s, %s)"
        % (a("PlayerFrame"), a("PetFrame"), a("TargetFrame"), a("TargetFrameToT")),
        a("PlayerFrame") == 0 and a("PetFrame") == 0 and a("TargetFrame") == 0 and a("TargetFrameToT") == 0
        and d1.sw.player == "hidden" and d1.sw.target == "hidden")
    chk("... the nameplates and the personal bar wanted (np %s, prd %s)" % (G.WowCompanion.npWant.np, G.WowCompanion.npWant.prd),
        G.WowCompanion.npWant.np is True and G.WowCompanion.npWant.prd is True and d1.sw.np is True and d1.sw.prd is True)
    # EACH ALONE
    sw(None, SW.PLAYER_SHOW)
    chk("the player frame alone, no pet strip: shown (%s, the pet's as the player's %s), the target still hidden (%s)" % (a("PlayerFrame"), a("PetFrame"), a("TargetFrame")),
        a("PlayerFrame") == 1 and a("PetFrame") == 1 and a("TargetFrame") == 0 and a("TargetFrameToT") == 0)
    # hub26: while OUR target bar is on (its default) the game's target frame stays hidden, whatever its own switch says
    sw(None, SW.TARGET_SHOW)
    chk("hub26: our target bar on: the game's target frame stays hidden (%s, its target %s)" % (a("TargetFrame"), a("TargetFrameToT")),
        a("TargetFrame") == 0 and a("TargetFrameToT") == 0 and doc().sw.uft is True)
    sw(None, SW.UFT_OFF)                                   # the player picks the game's frame instead
    chk("hub26: our target bar off: the switch reads false, not absent (%s)" % doc().sw.uft, doc().sw.uft is False)
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
    # hub33 (the user, 2026-11-11: "find a way to include Pet HP in the HUD, and better distinguish the player HUD vs Pet HUD"): the pet's
    # strip's own switch on "uisw" 32 / 33 (WC.npWant.pet, on unless turned off); the game's pet frame its own group "pet": as the player
    # frame (review fix 2026-11-11: our strip alone never hides it - the strip fades with the HUD, the game's frame would be gone too)
    dp0 = doc()
    chk("hub33: the pet's switch: cols 32 / 33, on before any tap (%s), not yet set (%s)" % (dp0.sw.pet, dp0.sw.set.pet),
        dp0.sw.cols.PET_OFF == 32 and dp0.sw.cols.PET_ON == 33 and dp0.sw.pet is True and dp0.sw.set.pet is None)
    sw(None, SW.PET_OFF)
    dp1 = doc()
    chk("hub33: 32 off (npWant.pet %s, sw.pet %s, set %s)" % (G.WowCompanion.npWant.pet, dp1.sw.pet, dp1.sw.set.pet),
        G.WowCompanion.npWant.pet is False and dp1.sw.pet is False and dp1.sw.set.pet is True)
    sw(None, SW.PET_ON)
    dp2 = doc()
    chk("hub33: 33 on again (%s, %s)" % (G.WowCompanion.npWant.pet, dp2.sw.pet), G.WowCompanion.npWant.pet is True and dp2.sw.pet is True)
    L2.execute("PETON = true; WowCompanion.prd = { petOn = function() return PETON end, wantedPet = function() return PETON end }")
    sw(None, SW.PLAYER_SHOW)
    chk("hub33 review: the player frame SHOWN and our strip on (the HUD may be faded): the game's pet frame stays shown (%s, %s)"
        % (a("PetFrame"), a("PlayerFrame")), a("PetFrame") == 1 and a("PlayerFrame") == 1)
    G.PETON = False
    sw(None, SW.PET_OFF)
    chk("hub33: our pet strip off: the game's pet frame as the player frame (%s)" % a("PetFrame"), a("PetFrame") == 1)
    sw(None, SW.PLAYER_HIDE)
    chk("hub33: ... and hidden with it (%s, %s)" % (a("PlayerFrame"), a("PetFrame")), a("PlayerFrame") == 0 and a("PetFrame") == 0)
    G.PETON = True
    sw(None, SW.PRD_ON)
    chk("hub33: the player frame hidden, our strip on: the game's pet frame hidden with it (%s)" % a("PetFrame"), a("PetFrame") == 0)
    sw(None, SW.PLAYER_SHOW)
    chk("hub33 review: the player frame shown again: the pet frame with it, our strip on or not (%s)" % a("PetFrame"), a("PetFrame") == 1)
    sw(None, SW.PLAYER_HIDE)
    L2.execute("WowCompanion.prd = nil")
    sw(None, SW.PET_ON); sw(None, SW.NP_ON)
    chk("alpha only: no Show / Hide on any game frame (%d / %d)" % (G.CALLS.show, G.CALLS.hide), G.CALLS.show == 0 and G.CALLS.hide == 0)
    # hub28 / hub30: COMBAT AUTO SHOT'S THIRD CHOICE (the user, 2026-11-10: on by default "incase someone else pulls me into combat", off
    # when "I target something out of combat AND start attack", back on "when an ability is used before that ability fires", never for
    # Hunter's Mark)
    L2.execute(r"""
        CV = { autoRangedCombat = "0" }; FIGHT = false; REFUSE = false; HASTGT = true
        C_CVar = { GetCVarBool = function(k) return CV[k] == "1" end,
                   SetCVar = function(k, v) if FIGHT and REFUSE then error("blocked") end; CV[k] = v end }
        C_Spell = { GetSpellName = function(id) if id == 1130 or id == 14325 then return "Hunter's Mark" end return "Spell" .. id end }
        function InCombatLockdown() return FIGHT end
        function UnitExists(u) return u == "target" and HASTGT end
        function FIRE(e, ...) for i = 1, #MADE do local f = MADE[i]; if f.OnEvent then pcall(f.OnEvent, f, e, ...) end end end
    """)
    cv = lambda: G.CV.autoRangedCombat
    sw(None, SW.SHOTF_ON)
    ds0 = doc()
    chk("hub30: the third choice chosen: on at once, out of a fight too (%s), the doc's shotf (%s, set %s), cols 28 / 29"
        % (cv(), ds0.sw.shotf, ds0.sw.set.shotf), cv() == "1" and ds0.sw.shotf is True and ds0.sw.set.shotf is True
        and ds0.sw.cols.SHOTF_OFF == 28 and ds0.sw.cols.SHOTF_ON == 29)
    G.FIRE("PLAYER_ENTER_COMBAT")
    chk("hub30: an attack started out of a fight with a target: off (%s)" % cv(), cv() == "0")
    G.FIRE("UNIT_SPELLCAST_SENT", "player", "", "g1", 1130)
    G.FIRE("UNIT_SPELLCAST_SENT", "player", "", "g2", 14325)
    chk("hub30: Hunter's Mark (any rank) does not turn it back on (%s)" % cv(), cv() == "0")
    G.FIRE("UNIT_SPELLCAST_SENT", "player", "", "g3", 75)
    G.FIRE("UNIT_SPELLCAST_SENT", "player", "", "g4", 6603)
    chk("hub30: Auto Shot / Attack themselves do not either (%s)" % cv(), cv() == "0")
    G.FIRE("UNIT_SPELLCAST_SENT", "party1", "", "g5", 3044)
    chk("hub30: someone else's cast changes nothing (%s)" % cv(), cv() == "0")
    G.FIRE("UNIT_SPELLCAST_SENT", "player", "", "g6", 3044)
    chk("hub30: an ability (Arcane Shot) turns it back on at its send (%s)" % cv(), cv() == "1")
    G.FIGHT = True; G.FIRE("PLAYER_REGEN_DISABLED")
    G.FIRE("PLAYER_ENTER_COMBAT")
    chk("hub30: an attack started INSIDE a fight leaves it on (%s)" % cv(), cv() == "1")
    G.FIGHT = False; G.FIRE("PLAYER_ENTER_COMBAT")
    G.FIGHT = True; G.REFUSE = True; G.FIRE("UNIT_SPELLCAST_SENT", "player", "", "g7", 3044)
    ds2 = doc()
    chk("hub30: the game refusing a write inside a fight: no error, the doc says so (shotfk %s)" % ds2.sw.shotfk, ds2.sw.shotfk is False)
    G.FIGHT = False; G.REFUSE = False; G.FIRE("PLAYER_REGEN_ENABLED")
    chk("hub30: the fight's end turns it on again (%s)" % cv(), cv() == "1")
    G.HASTGT = False; G.FIRE("PLAYER_ENTER_COMBAT")
    chk("hub30: no target: an attack start changes nothing (%s)" % cv(), cv() == "1")
    sw(None, SW.SHOTF_OFF); sw(None, SW.SHOT_OFF)
    G.HASTGT = True; G.FIRE("UNIT_SPELLCAST_SENT", "player", "", "g8", 3044)
    ds3 = doc()
    chk("hub30: the third choice off, Off chosen: the rules stop (%s, shotf %s)" % (cv(), ds3.sw.shotf), cv() == "0" and ds3.sw.shotf is False)
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
    G3.ROWS.uisw(None, G3.WowCompanion.hub.SW.UFT_OFF)        # hub26: the game's target frame chosen (our bar off)
    G3.ROWS.uisw(None, G3.WowCompanion.hub.SW.TARGET_SHOW)
    G3.ROWS.ui(None, 35)
    chk("a switch once set wins over the bundle: the target shown (%s) while HUD mode hides the player (%s)"
        % (G3.TargetFrame.alpha, G3.PlayerFrame.alpha), G3.TargetFrame.alpha == 1 and G3.PlayerFrame.alpha == 0)
    # hub32 (issue #120, a Rogue: "I can't see the combo points that are accumulated during combat"): the game's combo points sit on a
    # child of TargetFrame (Classic's ComboFrame); a child's alpha is its parent's times its own, so HUD mode hiding the target frame hid
    # them. While the host is hidden by us and our target bar (its own pips) is off, the combo frame ignores its parent's alpha.
    L3.execute(r'''
        local CF = {}
        CF.__index = CF
        function CF:SetIgnoreParentAlpha(v) self.ipa = v; self.n = (self.n or 0) + 1 end
        function CF:SetAlpha(a) error("hub32: the combo frame's own alpha is never written") end
        ComboFrame = setmetatable({}, CF)
        ComboPointPlayerFrame = setmetatable({}, CF)
    ''')
    SW3 = G3.WowCompanion.hub.SW
    G3.ROWS.uisw(None, SW3.TARGET_HIDE)            # our bar still off (UFT_OFF above): the game's target frame hidden by HUD mode
    G3.ROWS.uisw(None, SW3.PLAYER_SHOW)
    chk("hub32: the target frame hidden (%s), our bar off: the game's combo points lifted out of its alpha (%s)"
        % (G3.TargetFrame.alpha, G3.ComboFrame.ipa), G3.TargetFrame.alpha == 0 and G3.ComboFrame.ipa is True)
    chk("hub32: the player frame shown: its combo frame follows its parent (%s)" % G3.ComboPointPlayerFrame.ipa,
        G3.ComboPointPlayerFrame.ipa is False)
    G3.ROWS.uisw(None, SW3.TARGET_SHOW)
    chk("hub32: the target frame shown again: the combo frame follows its parent again (%s)" % G3.ComboFrame.ipa,
        G3.TargetFrame.alpha == 1 and G3.ComboFrame.ipa is False)
    G3.ROWS.uisw(None, SW3.TARGET_HIDE)
    G3.ROWS.uisw(None, SW3.UFT_ON)                 # our bar on: it has the pips, the game's stay hidden with their frame
    chk("hub32: our target bar on: the game's combo points stay hidden with the target frame (%s)" % G3.ComboFrame.ipa,
        G3.ComboFrame.ipa is False)
    G3.ROWS.uisw(None, SW3.UFT_OFF)
    chk("hub32: our bar off again: lifted again (%s)" % G3.ComboFrame.ipa, G3.ComboFrame.ipa is True)
    hubsrc = open(HUB, encoding="utf-8").read()
    chk("hub37: the version tag (hub35 party block + hub36 Polestar hide)", 'local HV = "hub37"' in hubsrc)
    # hub31 / hub36 POLESTAR ON THE TOP SCREEN (the user, 2026-11-11, the switch on: "The polestar window randomly reappears a lot"):
    # Polestar 0.2.5 fades its windows back by itself with no Show (every fight's start / end: UI/GuideFrame.lua:2528-2540 ->
    # GF.ApplyAlpha(true) -> Anim.To -> o:SetAlpha per frame), fades each window in as it shows, and makes windows later. A stub with a
    # real hooksecurefunc / HookScript / C_Timer, Polestar's Anim tween played step by step.
    L4 = tc.lupa.LuaRuntime()
    L4.execute(STUB + r'''
        MADE = {}; TICKERS = {}
        local base = CreateFrame
        function CreateFrame(kind, name, parent)
            local f = base(); f.parent = parent; f.hooks = {}; MADE[#MADE + 1] = f
            if name then _G[name] = f end
            return f
        end
        function Frame:GetParent() return self.parent end
        function Frame:SetParent(p) self.parent = p end
        function Frame:GetObjectType() return "Frame" end
        function Frame:IsProtected() return self.prot == true end
        function Frame:HookScript(k, fn) self.hooks[k] = self.hooks[k] or {}; table.insert(self.hooks[k], fn) end
        function Frame:Show() CALLS.show = CALLS.show + 1; if self.OnShow then self.OnShow(self) end
            for _, fn in ipairs(self.hooks.OnShow or {}) do fn(self) end end
        function hooksecurefunc(t, k, fn)
            if type(t) == "string" then return end
            local orig = t[k]
            rawset(t, k, function(...) local r = orig(...); fn(...); return r end)
        end
        C_Timer = { After = function() end, NewTicker = function(s, fn) TICKERS[s] = fn end }
        UIParent = setmetatable({ hooks = {} }, Frame)
        Minimap = setmetatable({ hooks = {} }, Frame)
        -- Polestar's Anim.To(obj, "alpha", from, to): SetAlpha(from), then steps, then SetAlpha(to) (Theme/Anim.lua:70-80, :105-140)
        function FADE(o, from, to) o:SetAlpha(from); for i = 1, 4 do o:SetAlpha(from + (to - from) * i / 5) end; o:SetAlpha(to) end
        GF = CreateFrame("Frame", "PolestarGuideFrame", UIParent)
        ARROW = CreateFrame("Frame", "PolestarNavArrow", UIParent)
        HINTS = CreateFrame("Frame", "PolestarPadHints", UIParent)
        STEP = CreateFrame("Button", "PolestarStepButton", UIParent); STEP.prot = true
        MMB = CreateFrame("Button", "PolestarMinimapButton", Minimap)
        OTHER = CreateFrame("Frame", "SomeoneElsesFrame", UIParent)
    ''')
    L4.execute(open(HUB, encoding="utf-8").read().replace("local ADDON = ...", "local ADDON = 'WowCompanionHub'", 1))
    G4 = L4.globals()
    for i in range(1, len(G4.MADE) + 1):
        if G4.MADE[i].OnEvent:
            G4.MADE[i].OnEvent(G4.MADE[i], "PLAYER_LOGIN")
    SW4, H4 = G4.WowCompanion.hub.SW, G4.WowCompanion.hub
    quick, slow = G4.TICKERS[0.25], G4.TICKERS[5]
    chk("hub36: a 0.25 s look beside the 5 s one", quick is not None and slow is not None)
    G4.ARROW.SetAlpha(G4.ARROW, 0.7)                       # the arrow's own nav.arrowAlpha (Nav/Arrow.lua:604) before the switch
    shows0 = G4.CALLS.show
    G4.ROWS.uisw(None, SW4.PS_HIDE)
    chk("hub31: on: Polestar's window, arrow, pad hints, secure step button at 0 (%s %s %s %s)"
        % (G4.GF.alpha, G4.ARROW.alpha, G4.HINTS.alpha, G4.STEP.alpha),
        G4.GF.alpha == 0 and G4.ARROW.alpha == 0 and G4.HINTS.alpha == 0 and G4.STEP.alpha == 0)
    chk("hub31: a frame under the minimap and someone else's frame untouched (never written, no hook)",
        L4.eval("rawget(MMB, 'alpha') == nil and rawget(OTHER, 'alpha') == nil and rawget(MMB, 'SetAlpha') == nil and rawget(OTHER, 'SetAlpha') == nil"))
    chk("hub36: the protected frame gets no SetAlpha hook (its method is the stub's own)", L4.eval("rawget(STEP, 'SetAlpha') == nil"))
    L4.execute("FADE(GF, GF:GetAlpha(), 1)")               # a fight starts / ends: GF.ApplyAlpha(true)
    chk("hub36: Polestar's combat fade (GuideFrame.lua:1652) leaves its window at 0 (%s)" % G4.GF.alpha, G4.GF.alpha == 0)
    L4.execute("GF:SetAlpha(1)")                           # a setting change: GF.ApplyAlpha(false)
    chk("hub36: Polestar setting its own alpha 1 (GuideFrame.lua:1655): still 0 (%s)" % G4.GF.alpha, G4.GF.alpha == 0)
    L4.execute("GF.OnShow = function(self) FADE(self, 0, 1) end; GF:Show()")   # re-shown with a fade-in (Report / Copy style OnShow)
    chk("hub36: re-shown with its own fade-in: still 0 (%s)" % G4.GF.alpha, G4.GF.alpha == 0)
    L4.execute('TOAST = CreateFrame("Frame", "PolestarRareToast", UIParent); TOAST:Show(); FADE(TOAST, 0, 1)')
    t0 = G4.TOAST.alpha
    quick()
    t1 = G4.TOAST.alpha
    L4.execute("FADE(TOAST, 0, 1)")
    chk("hub36: a window made later (the rare toast, Rares/Toast.lua:78 / :187): 0 at the next 0.25 s look (%s -> %s), its next fade too (%s)"
        % (t0, t1, G4.TOAST.alpha), t0 == 1 and t1 == 0 and G4.TOAST.alpha == 0)
    L4.execute('LATE = CreateFrame("Frame", "PolestarSomethingNew", UIParent); LATE:SetAlpha(1)')
    quick()
    l0 = G4.LATE.alpha
    slow()
    chk("hub36: an unknown Polestar name: the 5 s look finds it (%s -> %s)" % (l0, G4.LATE.alpha), l0 == 1 and G4.LATE.alpha == 0)
    L4.execute("STEP.alpha = 1")                           # anything that gave a protected frame its alpha back
    quick()
    chk("hub36: the 0.25 s look puts a drifted frame back to 0 (%s)" % G4.STEP.alpha, G4.STEP.alpha == 0)
    L4.execute("HINTS:SetParent(GF); HINTS:SetAlpha(1)")   # the pad hints re-parented into the window (UI/Gamepad.lua:1501)
    chk("hub36: the pad hints re-parented into the window: still 0 (%s)" % G4.HINTS.alpha, G4.HINTS.alpha == 0)
    L4.execute("ARROW:SetAlpha(0.8)")                      # Polestar's latest wish for the arrow while hidden
    shows1 = G4.CALLS.show
    G4.ROWS.uisw(None, SW4.PS_SHOW)
    chk("hub36: off: each frame back to the alpha Polestar last asked for - window %s, toast %s, late %s, step %s, hints %s (re-parented), arrow %s"
        % (G4.GF.alpha, G4.TOAST.alpha, G4.LATE.alpha, G4.STEP.alpha, G4.HINTS.alpha, G4.ARROW.alpha),
        G4.GF.alpha == 1 and G4.TOAST.alpha == 1 and G4.LATE.alpha == 1 and G4.STEP.alpha == 1 and G4.HINTS.alpha == 1
        and abs(G4.ARROW.alpha - 0.8) < 1e-9)
    L4.execute("FADE(GF, 1, 0.5)")
    quick(); slow()
    chk("hub36: off: Polestar's own fades pass through (%s)" % G4.GF.alpha, abs(G4.GF.alpha - 0.5) < 1e-9)
    chk("hub36: never Show / Hide from us (Show calls only the test's own: %d -> %d, hide %d)" % (shows0, shows1, G4.CALLS.hide),
        shows1 - shows0 == 2 and G4.CALLS.hide == 0)
    G4.ROWS.uisw(None, SW4.PS_HIDE)
    L4.execute("FADE(GF, 0, 1)")
    chk("hub36: on again: the hooks still hold (%s)" % G4.GF.alpha, G4.GF.alpha == 0)
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
    # hub33 / prd11: Trail's own answer Hub reads for the pet frame's group
    T.WowCompanion.npWant = LT.table_from({"prd": True})
    on1 = P.petOn()
    T.WowCompanion.npWant.pet = False
    on2 = P.petOn()
    T.WowCompanion.npWant = LT.table_from({"prd": False})
    on3 = P.petOn()
    T.WowCompanion.npWant = LT.table_from({"hud": True})
    on4 = P.petOn()
    chk("prd11: petOn: bar on -> %s; its own off -> %s; bar off -> %s; HUD mode's bundle -> %s" % (on1, on2, on3, on4),
        on1 is True and on2 is False and on3 is False and on4 is True)
    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
