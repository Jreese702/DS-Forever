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
        CV = { autoRangedCombat = "0" }; FIGHT = false; REFUSE = false; HASTGT = true; A6603 = false; WRITES = {}
        C_CVar = { GetCVarBool = function(k) return CV[k] == "1" end,
                   SetCVar = function(k, v) if FIGHT and REFUSE then error("blocked") end; CV[k] = v; WRITES[#WRITES + 1] = { v = v, lock = FIGHT } end }
        C_Spell = { GetSpellName = function(id) if id == 1130 or id == 14325 then return "Hunter's Mark" end return "Spell" .. id end }
        function InCombatLockdown() return FIGHT end
        function UnitExists(u) return u == "target" and HASTGT end
        function UnitCanAttack() return true end
        function UnitIsDeadOrGhost() return false end
        function IsCurrentSpell(id) if id == 6603 then return A6603 end return false end
        function FIRE(e, ...) for i = 1, #MADE do local f = MADE[i]; if f.OnEvent then pcall(f.OnEvent, f, e, ...) end end end
        -- hub43: a clock and C_Timer.After
        NOW = 100; TIMERS = {}
        function GetTime() return NOW end
        C_Timer = C_Timer or {}
        C_Timer.After = function(s, fn) TIMERS[#TIMERS + 1] = { at = NOW + s, fn = fn } end
        function advance(s)
            NOW = NOW + s
            local due, keep = {}, {}
            for _, t in ipairs(TIMERS) do if t.at <= NOW then due[#due + 1] = t else keep[#keep + 1] = t end end
            TIMERS = keep
            for _, t in ipairs(due) do pcall(t.fn) end
        end
    """)
    cv = lambda: G.CV.autoRangedCombat
    adv = G.advance
    sw(None, SW.SHOTF_ON)
    ds0 = doc()
    chk("hub30: the third choice chosen: on at once, out of a fight too (%s), the doc's shotf (%s, set %s), cols 28 / 29"
        % (cv(), ds0.sw.shotf, ds0.sw.set.shotf), cv() == "1" and ds0.sw.shotf is True and ds0.sw.set.shotf is True
        and ds0.sw.cols.SHOTF_OFF == 28 and ds0.sw.cols.SHOTF_ON == 29)
    G.A6603 = True
    G.FIRE("PLAYER_ENTER_COMBAT")
    chk("hub30: an attack started out of a fight with a target: off at once (%s)" % cv(), cv() == "0")
    # (hub44 review fix: a Mark SENT within PULL_UNDO_S of the attack start is the macro's own Mark pull, 5f; hub30's "Mark after a
    # melee pull" comes later than that)
    adv(1.0)
    G.FIRE("UNIT_SPELLCAST_SENT", "player", "", "g1", 1130)
    G.FIRE("UNIT_SPELLCAST_SENT", "player", "", "g2", 14325)
    G.FIRE("UNIT_SPELLCAST_SUCCEEDED", "player", "g2", 14325)
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
    G.FIGHT = False; adv(5); G.FIRE("PLAYER_ENTER_COMBAT"); adv(0.4)
    G.FIGHT = True; G.REFUSE = True; G.FIRE("UNIT_SPELLCAST_SENT", "player", "", "g7", 3044)
    ds2 = doc()
    chk("hub30: the game refusing a write inside a fight: no error, the doc says so (shotfk %s)" % ds2.sw.shotfk, ds2.sw.shotfk is False)
    G.FIGHT = False; G.REFUSE = False; G.FIRE("PLAYER_REGEN_ENABLED")
    chk("hub30: the fight's end turns it on again (%s)" % cv(), cv() == "1")
    G.HASTGT = False; G.FIRE("PLAYER_ENTER_COMBAT")
    chk("hub30: no target: an attack start changes nothing (%s)" % cv(), cv() == "1")
    G.HASTGT = True
    # hub43 (the user, 2026-11-12, a level 15 Hunter: "sometimes when using arcane shot my auto fire just stops and my dude stands
    # there"): every harmful spell from the controller runs PadMap's "/startattack [harm,nodead]" first, so the attack start is often
    # an ability's own; the 0 must never stick into the fight from one
    def fresh():
        G.FIGHT = False; G.REFUSE = False
        G.FIRE("PLAYER_REGEN_ENABLED"); adv(10)
        G.WRITES = L2.table(); G.A6603 = True; G.HASTGT = True

    def zeros(lock=None):
        return [G.WRITES[i] for i in range(1, len(G.WRITES) + 1)
                if G.WRITES[i].v == "0" and (lock is None or G.WRITES[i].lock is lock)]

    def sent(spell, g="gx"):
        G.FIRE("UNIT_SPELLCAST_SENT", "player", "", g, spell)
    fresh(); sent(3044); G.FIRE("PLAYER_ENTER_COMBAT"); adv(0.4)
    chk("hub43 1: Arcane Shot's SEND, then its /startattack's attack start: stays on (%s, 0 writes %d)" % (cv(), len(zeros())),
        cv() == "1" and not zeros())
    fresh(); G.FIRE("PLAYER_ENTER_COMBAT"); adv(0.1); sent(3044); adv(0.4)
    chk("hub43 2: the attack start, then Arcane Shot's SEND: on again, out of the lockdown (%s, 0 writes %d, in lockdown %d)"
        % (cv(), len(zeros()), len(zeros(True))), cv() == "1" and len(zeros(False)) == 1 and not zeros(True))
    fresh(); G.FIRE("PLAYER_ENTER_COMBAT"); adv(0.4)
    chk("hub43 3: a plain melee pull (no ability): off (%s)" % cv(), cv() == "0")
    fresh(); G.FIRE("PLAYER_ENTER_COMBAT"); adv(0.05); G.FIGHT = True; G.FIRE("PLAYER_REGEN_DISABLED"); adv(0.4)
    chk("hub43 4: a plain pull in swing range, the fight 0.05 s later: still off, the 0 written before the lockdown (%s, %d / %d)"
        % (cv(), len(zeros(False)), len(zeros(True))), cv() == "0" and len(zeros(False)) == 1 and not zeros(True))
    fresh(); G.FIRE("UNIT_SPELLCAST_FAILED", "player", "gf", 3044); G.FIRE("PLAYER_ENTER_COMBAT"); adv(0.4)
    chk("hub43 4b: a cast that failed unsent, its /startattack's attack start: stays on (%s, 0 writes %d)" % (cv(), len(zeros())),
        cv() == "1" and not zeros())
    fresh(); G.FIRE("UNIT_SPELLCAST_SUCCEEDED", "player", "gs", 3044); G.FIRE("PLAYER_ENTER_COMBAT")
    chk("hub43 4c: a SUCCEEDED alone is no press: a plain pull, off (%s)" % cv(), cv() == "0")
    fresh(); G.FIRE("UNIT_SPELLCAST_FAILED", "player", "gf", 1130); G.FIRE("PLAYER_ENTER_COMBAT")
    chk("hub43 4d: a failed Hunter's Mark is still a plain pull: off (%s)" % cv(), cv() == "0")
    # hub44 (the user's pick, 2026-11-12: "Mark pull keeps Auto Shot"): a Hunter's Mark pull is no plain melee pull any more
    fresh(); sent(1130); G.FIRE("PLAYER_ENTER_COMBAT"); adv(0.4)
    chk("hub44 5: a Hunter's Mark pull (its SEND, the attack start) keeps Auto Shot: on (%s, 0 writes %d)" % (cv(), len(zeros())),
        cv() == "1" and not zeros())
    fresh(); G.FIRE("UNIT_SPELLCAST_SUCCEEDED", "player", "gm", 14325); adv(1.2); G.FIRE("PLAYER_ENTER_COMBAT"); adv(0.4)
    chk("hub44 5b: a Mark rank's SUCCEEDED, the attack start 1.2 s later: on (%s, 0 writes %d)" % (cv(), len(zeros())),
        cv() == "1" and not zeros())
    L2.execute("C_Spell.GetSpellName = function(id) if id == 1130 or id == 14325 or id == 99999 then return [[Hunter's Mark]] end return 'Spell' .. id end")
    fresh(); sent(99999); G.FIRE("PLAYER_ENTER_COMBAT"); adv(0.4)
    chk("hub44 5c: a Mark known by its name only: on (%s)" % cv(), cv() == "1" and not zeros())
    fresh(); sent(1130); adv(2.0); G.FIRE("PLAYER_ENTER_COMBAT"); adv(0.4)
    chk("hub44 5d: a Mark 2 s before the attack start is past MARK_S: a plain pull, off (%s)" % cv(), cv() == "0")
    fresh(); G.FIRE("PLAYER_ENTER_COMBAT"); adv(0.4)
    G.WRITES = L2.table()
    sent(1130); G.FIRE("UNIT_SPELLCAST_SUCCEEDED", "player", "gm", 1130); adv(0.2); G.FIGHT = True; G.FIRE("PLAYER_REGEN_DISABLED"); adv(0.4)
    ones = [G.WRITES[i] for i in range(1, len(G.WRITES) + 1) if G.WRITES[i].v == "1"]
    chk("hub44 5e: Mark alone never turns it on: after a plain pull's 0, Mark's SEND / SUCCEEDED and the fight's start leave it off (%s, 1 writes %d)"
        % (cv(), len(ones)), cv() == "0" and not ones)
    chk("hub44: MARK_S is 1.5 s", G.WowCompanion.hub.SHOT_T.MARK_S == 1.5)
    # hub44 review fix: a controller Mark is PadMap's attack-on-cast macro (/startattack THEN /cast Hunter's Mark): the attack start
    # comes first and writes the plain pull's 0, then Mark's SEND undoes that same press's 0 (within PULL_UNDO_S, out of the lockdown)
    fresh(); G.FIRE("PLAYER_ENTER_COMBAT"); sent(1130); G.FIRE("UNIT_SPELLCAST_SUCCEEDED", "player", "gm", 1130); adv(0.4)
    ones = [G.WRITES[i] for i in range(1, len(G.WRITES) + 1) if G.WRITES[i].v == "1"]
    chk("hub44 5f: the macro order (attack start, then Mark's SEND): on again, out of the lockdown (%s, 1 writes %d)" % (cv(), len(ones)),
        cv() == "1" and len(ones) == 1 and ones[0].lock is False)
    fresh(); G.FIRE("PLAYER_ENTER_COMBAT"); adv(0.1); sent(14325); adv(0.4)
    chk("hub44 5g: the macro order with a Mark rank 0.1 s behind: on (%s)" % cv(), cv() == "1")
    fresh(); G.FIRE("PLAYER_ENTER_COMBAT"); adv(2.0); G.WRITES = L2.table(); sent(1130); adv(0.4)
    ones = [G.WRITES[i] for i in range(1, len(G.WRITES) + 1) if G.WRITES[i].v == "1"]
    chk("hub44 5h: a Mark 2 s after a plain pull never turns it on (%s, 1 writes %d)" % (cv(), len(ones)), cv() == "0" and not ones)
    fresh(); G.FIRE("PLAYER_ENTER_COMBAT"); adv(0.05); G.FIGHT = True; G.FIRE("PLAYER_REGEN_DISABLED"); G.WRITES = L2.table()
    sent(1130); adv(0.4)
    ones = [G.WRITES[i] for i in range(1, len(G.WRITES) + 1) if G.WRITES[i].v == "1"]
    chk("hub44 5i: a Mark inside the fight's lockdown writes nothing (%s, 1 writes %d)" % (cv(), len(ones)), cv() == "0" and not ones)
    fresh(); G.FIRE("PLAYER_ENTER_COMBAT"); adv(0.1); sent(1130); G.WRITES = L2.table(); sent(1130); adv(0.4)
    ones = [G.WRITES[i] for i in range(1, len(G.WRITES) + 1) if G.WRITES[i].v == "1"]
    chk("hub44 5j: the undo is once per pull (a second Mark writes nothing more: %d)" % len(ones), not ones)
    chk("hub44: PULL_UNDO_S is 0.3 s", G.WowCompanion.hub.SHOT_T.PULL_UNDO_S == 0.3)
    fresh(); G.FIRE("UNIT_SPELLCAST_FAILED", "player", "gf", 3044); adv(2.5); G.FIRE("PLAYER_ENTER_COMBAT"); adv(0.4)
    c_fail = cv()
    G.FIGHT = True; G.FIRE("PLAYER_REGEN_DISABLED"); c_rd = cv(); sent(3044)
    d6 = doc()
    chk("hub43 6: a failed cast (no SEND), 2.5 s later a pull: off (%s), the fight's start alone leaves it (%s), the in-fight SEND: on (%s, shotfk %s)"
        % (c_fail, c_rd, cv(), d6.sw.shotfk), c_fail == "0" and c_rd == "0" and cv() == "1" and d6.sw.shotfk is True)
    fresh(); G.FIRE("UNIT_SPELLCAST_FAILED", "player", "gf", 3044); adv(2.5); G.FIRE("PLAYER_ENTER_COMBAT"); adv(0.4)
    G.FIGHT = True; G.REFUSE = True; G.FIRE("PLAYER_REGEN_DISABLED"); sent(3044)
    d6b = doc()
    chk("hub43 6b: ... the game refusing that write: no error, shotfk false (%s, %s)" % (cv(), d6b.sw.shotfk), cv() == "0" and d6b.sw.shotfk is False)
    fresh()
    G.WowCompanion.atkCell = L2.table_from({"row": "party", "col": 36})
    sw(None, SW.ATK_ON)
    G.A6603 = False; G.SENT = L2.table(); G.FIRE("PLAYER_TARGET_CHANGED")
    went = [G.SENT[i] for i in range(1, len(G.SENT) + 1) if G.SENT[i].type == "atkgo"]
    adv(1.0); G.A6603 = True; G.FIRE("PLAYER_ENTER_COMBAT"); adv(0.4)
    chk("hub43 7: our atkgo, then its attack start within 1.5 s: stays on (atkgo %d, %s)" % (len(went), cv()), len(went) == 1 and cv() == "1")
    fresh(); G.A6603 = False; G.FIRE("PLAYER_TARGET_CHANGED"); adv(2.0); G.A6603 = True; G.FIRE("PLAYER_ENTER_COMBAT"); adv(0.4)
    chk("hub43 7b: an attack start 2 s after the atkgo is a plain pull again: off (%s)" % cv(), cv() == "0")
    sw(None, SW.ATK_OFF); G.WowCompanion.atkCell = None
    fresh(); G.FIRE("PLAYER_ENTER_COMBAT"); adv(0.4)
    G.FIGHT = True; G.REFUSE = True; sent(3044); c_ref = cv(); G.REFUSE = False; adv(0.5); G.FIRE("PLAYER_REGEN_DISABLED")
    d8 = doc()
    chk("hub43 8: an ability's SEND refused, then the fight's start within 2 s: written again (%s -> %s, shotfk %s)" % (c_ref, cv(), d8.sw.shotfk),
        c_ref == "0" and cv() == "1" and d8.sw.shotfk is True)
    fresh(); G.FIRE("PLAYER_ENTER_COMBAT"); adv(0.4); adv(3); G.FIGHT = True; G.FIRE("PLAYER_REGEN_DISABLED")
    chk("hub43 8b: no ability within 2 s of the fight's start: left off (%s)" % cv(), cv() == "0")
    fresh()
    for i in range(30):
        sent(3044, "g%d" % i); adv(0.01)
    lg = doc().shotlog
    rows = [lg[i] for i in range(1, len(lg) + 1)] if lg is not None else []
    FIELDS = {"t", "ev", "spell", "cv", "a6603", "a75", "lock"}
    plain = all(set(r.keys()) <= FIELDS and all(isinstance(r[k], (int, float, str, bool)) for k in r.keys()) for r in rows)
    chk("hub43 9: the shot log: the last 24 only (%d), plain fields only (%s), the newest last (%s)"
        % (len(rows), plain, rows[-1].ev if rows else None),
        len(rows) == 24 and plain and rows[-1].ev in ("sent", "w1") and all(r.t is not None and r.ev for r in rows))
    G.FIGHT = False; G.FIRE("PLAYER_REGEN_ENABLED")
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
    chk("hub40: the version tag (hub38 the player / pet row + side tab art; hub39 int/1429; hub40 the party bars one column wide; hub41 the tab on a K change, pitch 64; hub42 the left column / the grid under the HUD)", 'local HV = "hub46"' in hubsrc and "hub45:" in hubsrc and "hub44:" in hubsrc and "hub42:" in hubsrc and "hub43:" in hubsrc
        and "Mark pull keeps Auto Shot" in hubsrc)
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
