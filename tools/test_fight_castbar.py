"""THE CAST BAR IN A TOP-SCREEN FIGHT, THE ADD-ON'S HALF (PadMap m187 / Book b77 / Hub hub76; ActionBar nbar13 is in
test_combat_bar_spot.py's section C), off-device. The user's ruling 2026-11-23: "When the controller moves to the top, the cast bar should
still show across the top of the bottom screen. We can add a setting ... to have the cast bar be on the top or the bottom. If its on the
top we should really just turn back on the cast bar for the top screen dynamically instead of trying to remake ours ... Same thing with
the swing timer."

    python -B tools/test_fight_castbar.py

Proven here (nbar18 / hub78 / m190 / b78, the user's reversal 2026-11-24: our own top bar; the game's casting bar never shown
again; the swing follows the cast bar and the app's Swing timer):
  1 PadMap m187: by default `cc` = 0 and combatCastTopOn() false (Bottom screen); the app's page-3 code 25 (padtab 39, 38, 37 then 25;
    Book b77) turns this character's flag on (WowCompanionSpellsDB.ctcb[name-realm] = 1, `cc` = 1, a padmap sent), 24 off; a code in a
    fight is remembered at once (a display flag: nothing protected); per character; `ct` (22 / 23) untouched by it and it by them; the
    add-on's Layouts reset clears it with ctop;
  2 Hub hub76: with the player's Hide on (hub11, cb.hidden) the game's casting bar is kept at alpha 0; WC.hubCastFight(true) (ActionBar's
    Wings in a Top-screen fight with the flag on) gives a CASTING bar its alpha back at once and stops zeroing it (the game's own
    ApplyAlpha / OnShow / fade animation are left to the game); the document keeps cb.hidden = true (the player's word: the app never
    re-taps) and says ft = 1; WC.hubCastFight(false) zeroes it again (a cast still running too) and the hooks zero it again; a bar the
    player SHOWS (cb.hidden false) is never touched by the fight's word; only SetAlpha is called on the game's bar (no Hide / Show /
    SetParent / SetPoint), no CVar or binding written;
  3 PadMap m188 (ActionBar nbar14's reads, the user's 11-23 B rule and swing pick): WC.padMap.fightSw is the router's own switch - off
    out of a fight, on in one, off once B gave the buttons back IN the fight (wc-given, as the router's secure snippet sets it), on again
    when re-armed; WC.padMap.gameSwingOn / dpadState `gsw` read Edit Mode's ShowSwingTimer (read-only; unknown = off), a change sends a
    padmap, nothing is read in a fight;
plus no spied call, no lockdown breach, no snippet error, no Lua error. Needs lupa. Exit code 1 on any failure."""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
TOP = open(os.path.join(HERE, "test_combat_top_flag.py"), encoding="utf-8").read()
# the flag test's world (test_party.py's stubbed game with the real Safety / TapPad / Book / PadMap / PadTabCtl): its helpers, not its run
NS = {"__file__": os.path.join(HERE, "test_combat_top_flag.py")}
exec(compile(TOP.split("\nNORMAL = ", 1)[0], "test_combat_top_flag.py", "exec"), NS)
boot, padtab, clean = NS["boot"], NS["padtab"], NS["clean"]
SRC = NS["SRC"]

fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' and not ok else ''))
    if ok:
        passes += 1
    else:
        fails += 1


def st(L):
    s = L.eval('WowCompanion.padMap.dpadState()')
    return s['ct'], s['cc']


def sent_cc(L, n0):
    out = []
    for i in range(n0 + 1, int(L.eval('#SENT')) + 1):
        try:
            d = json.loads(L.eval('SENT[%d]' % i))
        except Exception:      # noqa: BLE001
            continue
        if isinstance(d, dict) and d.get('type') == 'padmap':
            out.append(((d.get('m') or {}).get('dpad') or {}).get('cc'))
    return out


# ------------------------------------------------------------------------------------------------ 0 the tags
check('local PV = "m208"' in SRC["padmap"] and "m190 THE SWING WITH OUR TOP CAST BAR" in SRC["padmap"]
      and "m187 THE CAST BAR IN A TOP-SCREEN FIGHT" in SRC["padmap"]
      and "m179 THE COMBAT BAR ON THE TOP SCREEN" in SRC["padmap"], "0. PadMap m190 (m187's and m179's notes kept)")
check('local BV = "b90"' in SRC["book"] and "b77 THE CAST BAR IN A TOP-SCREEN FIGHT" in SRC["book"]
      and "for k = 0, 1 do P3[24 + k]" in SRC["book"] and "b78 THE SWING WITH OUR TOP CAST BAR" in SRC["book"]
      and "for k = 0, 1 do P3[26 + k]" in SRC["book"], "0. Book b78 (b77's codes 24 / 25 kept) and its codes 26 / 27")
check(len(re.findall(r'(?m)^local ', SRC["padmap"])) <= 200, "0. PadMap's main chunk keeps to Lua 5.1's 200 locals")

W = boot()
pm = 'WowCompanion.padMap'
W.execute(pm + '.dpadBar(true); runTimers(NOW + 1)')

# ------------------------------------------------------------------------------------------------ 1 PadMap m187 / Book b77
ct, cc = st(W)
check(cc == 0 and ct == 0 and W.eval(pm + '.combatCastTopOn()') is False, "1. no flag: cc = 0 (Bottom screen, the default)", (ct, cc))
n0 = int(W.eval('#SENT'))
padtab(W, 39, 38, 37, 25)
W.execute('runTimers(NOW + 1)')
check(W.eval('(WowCompanionSpellsDB.ctcb or {})[WHO .. "-" .. REALM]') == 1 and W.eval(pm + '.combatCastTopOn()') is True,
      "1. code 25: this character's flag on (ctcb[name-realm] = 1)")
ct, cc = st(W)
check(cc == 1 and ct == 0, "1. cc = 1, ct untouched (0)", (ct, cc))
check(1 in sent_cc(W, n0), "1. a padmap with cc = 1 sent (the app stops re-sending)", sent_cc(W, n0))
clean(W, "1")
padtab(W, 39, 38, 37, 23)
W.execute('runTimers(NOW + 1)')
check(st(W) == (1, 1), "1. code 23 (ctop on) leaves cc as it was", st(W))
padtab(W, 39, 38, 37, 24)
W.execute('runTimers(NOW + 1)')
check(st(W) == (1, 0) and W.eval('(WowCompanionSpellsDB.ctcb or {})[WHO .. "-" .. REALM]') == 0, "1. code 24: off again (an explicit 0), ct kept",
      st(W))
W.execute('fightOn()')
padtab(W, 39, 38, 37, 25)
W.execute('tick(0.5)')
check(W.eval(pm + '.combatCastTopOn()') is True, "1. in a fight: remembered at once (a display flag: nothing protected)")
clean(W, "1 (in the fight)")
W.execute('fightOff(); tick(0.5); runTimers(NOW + 2); tick(0.5)')
check(st(W)[1] == 1, "1. after the fight: still on")
W.execute('WowCompanionSpellsDB.ctcb["Other-" .. REALM] = 1; WowCompanionSpellsDB.ctcb[WHO .. "-" .. REALM] = nil')
check(st(W)[1] == 0 and W.eval(pm + '.combatCastTopOn()') is False, "1. per character (another's on: mine off)")
W.execute('WowCompanionSpellsDB.ctcb["Other-" .. REALM] = nil')
padtab(W, 39, 38, 37, 25)
W.execute('runTimers(NOW + 1)')
keys = W.eval('WowCompanion.dsfResetKeys.spells.layouts')
check(keys is not None and "ctcb" in [keys[i] for i in range(1, len(keys) + 1)], "1. ctcb is in the add-on's Layouts list (= ResetPlan's)")
W.execute('WowCompanion.dsfReset(21)')
check(W.eval('(WowCompanionSpellsDB.ctcb or {})[WHO .. "-" .. REALM]') is None and st(W)[1] == 0, "1. Layouts reset: the flag gone (Bottom)")
clean(W, "1 (reset)")

# ------------------------------------------------------------------------------------------------ 1b PadMap m190 / Book b78: the swing
def cs(Lx):
    return Lx.eval('WowCompanion.padMap.dpadState()')['cs']


check(cs(W) == 1 and W.eval(pm + '.combatCastSwingOn()') is True, "1b. no flag: cs = 1 (the swing with our top bar on: the default)", cs(W))
n0 = int(W.eval('#SENT'))
padtab(W, 39, 38, 37, 26)
W.execute('runTimers(NOW + 1)')
check(W.eval('(WowCompanionSpellsDB.ctsw or {})[WHO .. "-" .. REALM]') == 0 and W.eval(pm + '.combatCastSwingOn()') is False and cs(W) == 0,
      "1b. code 26: this character's swing off (ctsw[name-realm] = 0, cs = 0)", cs(W))
sw_sent = []
for i in range(n0 + 1, int(W.eval('#SENT')) + 1):
    try:
        d = json.loads(W.eval('SENT[%d]' % i))
    except Exception:      # noqa: BLE001
        continue
    if isinstance(d, dict) and d.get('type') == 'padmap':
        sw_sent.append(((d.get('m') or {}).get('dpad') or {}).get('cs'))
check(0 in sw_sent, "1b. a padmap with cs = 0 sent (the app stops re-sending)", sw_sent)
check(st(W) == (0, 0), "1b. ... ct and cc untouched", st(W))
padtab(W, 39, 38, 37, 25)
W.execute('runTimers(NOW + 1)')
check(cs(W) == 0 and st(W)[1] == 1, "1b. code 25 (cc on) leaves cs as it was", (cs(W), st(W)))
W.execute('fightOn()')
padtab(W, 39, 38, 37, 27)
W.execute('tick(0.5)')
check(W.eval(pm + '.combatCastSwingOn()') is True, "1b. code 27 in a fight: on again at once (a display flag)")
clean(W, "1b (in the fight)")
W.execute('fightOff(); tick(0.5); runTimers(NOW + 2); tick(0.5)')
W.execute('WowCompanionSpellsDB.ctsw["Other-" .. REALM] = 0')
check(cs(W) == 1, "1b. per character (another's off: mine on)")
padtab(W, 39, 38, 37, 26)
W.execute('runTimers(NOW + 1)')
keys = W.eval('WowCompanion.dsfResetKeys.spells.layouts')
check(keys is not None and "ctsw" in [keys[i] for i in range(1, len(keys) + 1)], "1b. ctsw is in the add-on's Layouts list (= ResetPlan's)")
W.execute('WowCompanion.dsfReset(21)')
check(W.eval('(WowCompanionSpellsDB.ctsw or {})[WHO .. "-" .. REALM]') is None and cs(W) == 1, "1b. Layouts reset: the flag gone (on)")
clean(W, "1b (reset)")

# ------------------------------------------------------------------------------------------------ 3 PadMap m188 (ActionBar nbar14's reads)
check("m188 THE B RULE AND THE GAME'S SWING TIMER" in SRC["padmap"], "3. PadMap m188's note")
W3 = boot()
W3.execute(pm + '.dpadBar(true); runTimers(NOW + 1)')
check(W3.eval('type(%s.fightSw)' % pm) == "function" and W3.eval(pm + '.fightSw()') is False,
      "3. m188 fightSw: the router's switch, off out of a fight with nothing armed")
W3.execute('fightOn(); tick(0.5)')
check(W3.eval(pm + '.fightSw()') is True, "3. ... in a fight: on (nothing given back)")
# B's give-back: the router's secure snippet sets wc-given on its buttons (here on one, as the snippet does through its frame refs)
W3.execute('IN_SECURE = true; local names = %s.routerButtons(); for _, n in pairs(names) do local b = _G[n]; if b then '
           'b:SetAttribute("wc-given", true); break end end; IN_SECURE = false' % pm)
check(W3.eval(pm + '.fightSw()') is False, "3. ... B gave the buttons back IN the fight: off (ActionBar's Wings slide down)")
W3.execute('IN_SECURE = true; local names = %s.routerButtons(); for _, n in pairs(names) do local b = _G[n]; if b then '
           'b:SetAttribute("wc-given", false) end end; IN_SECURE = false' % pm)
check(W3.eval(pm + '.fightSw()') is True, "3. ... re-armed (nothing given back again): on")
W3.execute('fightOff(); tick(0.5); runTimers(NOW + 2)')
# m190: m188's read of the GAME's own swing timer is gone (the swing goes where the cast bar goes): no gameSwingOn, no `gsw`, no Edit
# Mode read, a change of it sends nothing
check(W3.eval('type(%s.gameSwingOn)' % pm) == "nil" and W3.eval(pm + '.dpadState().gsw') is None,
      "3. m190: no gameSwingOn export, no gsw in dpadState")
n0 = int(W3.eval('#SENT'))
W3.execute('EDITREADS = 0; C_EditMode = { GetAccountSettings = function() EDITREADS = EDITREADS + 1; return { { setting = 37, value = 1 } } end }; '
           'Enum = Enum or {}; rawset(Enum, "EditModeAccountSetting", { ShowSwingTimer = 37 }); fire("EDIT_MODE_LAYOUTS_UPDATED"); runTimers(NOW + 1)')
check(W3.eval('EDITREADS') == 0, "3. m190: Edit Mode's account settings never read", W3.eval('EDITREADS'))
clean(W3, "3")

# ------------------------------------------------------------------------------------------------ 2 Hub hub76
from wowstub import ADDONS, boot_world      # noqa: E402

HUB = open(os.path.join(ADDONS, "WowCompanionHub", "Hub.lua"), encoding="utf-8").read()
check(re.search(r'local HV = "hub79"', HUB) is not None and "hub78 THE GAME'S CAST BAR STAYS AS THE PLAYER SET IT" in HUB
      and "hub76 THE GAME'S CAST BAR BACK FOR A TOP-SCREEN FIGHT" in HUB and "hub75 THE GAME'S LATENCY" in HUB,
      "2. Hub hub78 and its note (hub76's / hub75's kept)")

# the game's casting bar: a plain table with the methods Hub uses; every other call is logged (none may come)
PRE = r'''
CB_CALLS = {}
ANIM_STOPS = 0
local function bar(name)
    local f = { alpha = 1, casting = false, hooks = {}, name = name }
    f.SetAlpha = function(self, a) self.alpha = a; CB_CALLS[#CB_CALLS + 1] = "SetAlpha" end
    f.GetAlpha = function(self) return self.alpha end
    f.ApplyAlpha = function(self, a) self.alpha = a end
    f.HookScript = function(self, e, fn) self.hooks[e] = fn end
    -- the game's fade animations (hub76 review: one already playing when the bar is hidden again is stopped)
    for _, key in ipairs({ "HoldFadeOutAnim", "FadeOutAnim" }) do
        local ag = { playing = false, hooks = {} }
        ag.IsPlaying = function(self) return self.playing end
        ag.Stop = function(self) self.playing = false; ANIM_STOPS = ANIM_STOPS + 1 end
        ag.HookScript = function(self, e, fn) self.hooks[e] = fn end
        f[key] = ag
    end
    for _, m in ipairs({ "Hide", "Show", "SetParent", "SetPoint", "ClearAllPoints", "SetShown" }) do
        f[m] = function() CB_CALLS[#CB_CALLS + 1] = m end
    end
    _G[name] = f
    return f
end
CBF = bar("PlayerCastingBarFrame")
_G.__writes = 0
'''
L, errs = boot_world(pre=PRE, strict=False)
check(not errs, "2. the world loads (%s)" % "; ".join(errs)[:200])
L.execute('local function count() __writes = __writes + 1 end; _G.SetCVar = count; _G.SetBinding = count; _G.SetBindingClick = count')


def hubdoc():
    out = L.eval('''(function() local out = {}; local s = WowCompanion.send; WowCompanion.send = function(x) out[#out + 1] = x end
        pcall(WowCompanion.snapshot, "manual"); WowCompanion.send = s; return out end)()''')
    for i in range(len(out), 0, -1):
        try:
            d = json.loads(out[i])
        except Exception:      # noqa: BLE001
            continue
        if d.get("type") == "hub":
            return d["hub"]
    return None


check(L.eval('type(WowCompanion.hubCastFight)') == "nil" and L.eval('type(WowCompanion.hub.cbFight)') == "nil",
      "2. hub78: no WC.hubCastFight / hub.cbFight (the game's cast bar is never shown for a fight any more)")
L.execute('WowCompanion.hub.cbSet(true)')
check(L.eval('CBF.alpha') == 0, "2. the player's Hide: the game's casting bar at alpha 0", L.eval('CBF.alpha'))
L.execute('CBF.casting = true; CBF:ApplyAlpha(1)')       # a cast starts: the game puts its alpha back; hub11's post-hook zeroes it
L.execute('if CBF.hooks.OnShow then CBF.hooks.OnShow(CBF) end')
check(L.eval('CBF.alpha') == 0, "2. ... a cast while hidden stays at 0 (hub11's hooks)", L.eval('CBF.alpha'))
L.execute('_G.InCombatLockdown = function() return true end; CBF:ApplyAlpha(1); if CBF.hooks.OnShow then CBF.hooks.OnShow(CBF) end; '
          'WowCompanion.hub.cbCheck()')
check(L.eval('CBF.alpha') == 0, "2. ... in a fight too (no fight reveal): still 0", L.eval('CBF.alpha'))
d = hubdoc()
cbd = (d or {}).get("cb") or {}
check(cbd.get("hidden") is True and "ft" not in cbd, "2. the document: cb.hidden the player's word, no ft (hub76's reveal gone)", cbd)
# hidden again while a fade plays (an interrupt's HoldFadeOutAnim drives the alpha itself): stopped at once (hub76 review, kept)
L.execute('WowCompanion.hub.cbSet(false); CBF.alpha = 1; CBF.HoldFadeOutAnim.playing = true; ANIM_STOPS = 0')
L.execute('WowCompanion.hub.cbSet(true)')
check(L.eval('CBF.HoldFadeOutAnim.playing') is False and L.eval('ANIM_STOPS') == 1 and L.eval('CBF.alpha') == 0,
      "2. Hide while a fade plays stops it (no lingering bar) and zeroes the bar",
      (L.eval('CBF.HoldFadeOutAnim.playing'), L.eval('ANIM_STOPS'), L.eval('CBF.alpha')))
# the player SHOWS the game's bar: left alone (alpha as the game left it), in fights too
L.execute('WowCompanion.hub.cbSet(false); CBF.casting = false; CBF.alpha = 0.7; WowCompanion.hub.cbCheck()')
check(L.eval('CBF.alpha') == 0.7, "2. the player's Show: the bar never touched (alpha as the game left it)", L.eval('CBF.alpha'))
calls = [L.eval('CB_CALLS[%d]' % i) for i in range(1, int(L.eval('#CB_CALLS')) + 1)]
check(set(calls) <= {"SetAlpha"}, "2. only SetAlpha ever called on the game's bar (no Hide / Show / SetParent / SetPoint)", sorted(set(calls)))
check(L.eval('__writes') == 0, "2. no CVar or binding written", L.eval('__writes'))

print('\n%d passed, %d failed' % (passes, fails))
print('failed: %d' % fails)
sys.exit(1 if fails else 0)
