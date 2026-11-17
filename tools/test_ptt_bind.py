"""PUSH TO TALK (HOLD) AND THE POLESTAR MENU ON A CONTROLLER BUTTON (PadMap m170, Hub hub58), off-device. The user's picks,
2026-11-17: two new bindable DS commands, "Push to talk (hold)" and "Polestar menu".

    python tools/test_ptt_bind.py

Proven here, on the REAL add-on files:
  1 the tags: PadMap m170, Hub hub58, each header note quoting the user's picks; both compile as Lua 5.1;
  2 PadMap: two DS Forever commands, DSPTT "Push to talk (hold)" / DSPSMENU "Polestar menu", listed right after the Polestar step
    commands (the DS tab, keys "DS..."), the bodies "/click WowCompanionPadTalk / WowCompanionPadPolestarMenu", macro names in 16
    characters; put on a button (WoW Defaults) and on a fight layer (In combat) as any DS command, and run in a fight (the fight
    switch's rules are the router's); no spied call, no lockdown breach;
  3 Hub: the two plain buttons exist; a click sends ONE barcmd talk / psmenu (out of a fight and in one); talk holds the chat guest
    in the same click (hub7: the hold's presses never reach the game), psmenu holds nothing; neither calls Polestar nor the voice.
Needs lupa. Exit code 1 on any failure."""
import os
import re
import sys

from lupa import lua51 as lupa

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ADDONS = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons")
PADMAP = open(os.path.join(ADDONS, "WowCompanionSpells", "PadMap.lua"), encoding="utf-8").read()
HUB = open(os.path.join(ADDONS, "WowCompanionHub", "Hub.lua"), encoding="utf-8").read()
QUOTE = '"Push to talk (hold)" and "Polestar menu"'

fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


# ================================================================ 1. the tags
check('local PV = "m172"' in PADMAP and PADMAP.count(QUOTE) >= 2, "1: PadMap says m170, its notes quote the user's picks")
check(int(re.search(r'local HV = "hub(\d+)"', HUB).group(1)) >= 58 and HUB.count(QUOTE) >= 2, "1: Hub says hub58+ (hub59 after the 11-17 merge), its notes quote the user's picks")
comp = lupa.LuaRuntime().eval('function(s, n) local f, e = loadstring(s, n); return e end')
for name, src in (("PadMap.lua", PADMAP), ("Hub.lua", HUB)):
    e = comp(src, name)
    check(e is None, "1: Lua 5.1 compiles %s (200 locals, 60 upvalues)" % name, e or '')

# ================================================================ 2. PadMap's two commands (test_fight_layer.py's world)
FLP = os.path.join(HERE, "test_fight_layer.py")
FL = open(FLP, encoding="utf-8").read()
ns = {"__file__": FLP}
exec(FL[:FL.index("fails = 0")] + FL[FL.index("# what this test adds"):FL.index("comp = lupa.LuaRuntime()")], ns)
W = ns['boot']()
check(list(W.eval('ERRS').values()) == [] and W.eval('#SNIPERR') == 0, "2: the world loads", list(W.eval('ERRS').values())[:2])
el = W.eval('WowCompanion.padMap.extraList()')
keys = [el[i].key for i in range(1, len(el) + 1)]
names = {el[i].key: el[i].n for i in range(1, len(el) + 1)}
check('DSPTT' in keys and 'DSPSMENU' in keys and keys.index('DSPTT') == keys.index('DSPSPREV') + 1
      and keys.index('DSPSMENU') == keys.index('DSPTT') + 1,
      "2: Push to talk / Polestar menu listed right after the Polestar step commands (DS Forever's own: the DS tab)", keys[:14])
check(names.get('DSPTT') == 'Push to talk (hold)' and names.get('DSPSMENU') == 'Polestar menu',
      "2: their names", (names.get('DSPTT'), names.get('DSPSMENU')))
W.execute('WowCompanion.padMap.emit(true)')
cmds = {c['key']: c for c in ns['docs'](W, 'padmap')[-1]['m'].get('commands', [])}
pt, pm = cmds.get('DSPTT') or {}, cmds.get('DSPSMENU') or {}
check(pt.get('b') == '/click WowCompanionPadTalk' and pm.get('b') == '/click WowCompanionPadPolestarMenu',
      "2: the document's commands: each clicks its Hub button", (pt.get('b'), pm.get('b')))
check(isinstance(pt.get('i'), int) and pt.get('i') > 0 and isinstance(pm.get('i'), int) and pm.get('i') > 0,
      "2: each has a file icon (the app draws its kit art over it: PadMapModel.kitArt)", (pt.get('i'), pm.get('i')))
mn = re.findall(r'key = "(?:DSPTT|DSPSMENU)", mn = "([^"]+)"', PADMAP)
check(len(mn) == 2 and all(len(m) <= 16 for m in mn), "2: their macro names fit the game's 16 characters", mn)

ok = W.eval('WowCompanion.padMap.putCommand(1, 1, 3, "DSPTT")')
sl = ns['slot_doc'](W, 1, 3)
check(ok is True and sl.get('kind') == 'command' and sl.get('key') == 'DSPTT' and sl.get('b') == '/click WowCompanionPadTalk',
      "2: Push to talk put on the bare D-pad Right (WoW Defaults): the slot carries the command", sl)
ok = W.eval('WowCompanion.padMap.putFight(1, 1, 2, "command", "DSPSMENU")')
check(ok is True and W.eval('HDR("wc-ft-2")') == "/click WowCompanionPadPolestarMenu",
      "2: the Polestar menu on D-pad Up's In combat layer: the router's fight text clicks the Hub button", W.eval('HDR("wc-ft-2")'))
ok = W.eval('WowCompanion.padMap.putFight(1, 1, 4, "command", "DSPTT")')
check(ok is True and W.eval('HDR("wc-ft-4")') == "/click WowCompanionPadTalk",
      "2: Push to talk on D-pad Down's In combat layer: the router's fight text clicks the Hub button", W.eval('HDR("wc-ft-4")'))
W.execute('fightOn()')
a, kind, mt = W.eval('key("PADDUP")')
check(kind == "slotfight" and mt == "/click WowCompanionPadPolestarMenu", "2: in a fight D-pad Up opens the Polestar menu", (a, kind, mt))
a, kind, mt = W.eval('key("PADDDOWN")')
check(kind == "slotfight" and mt == "/click WowCompanionPadTalk", "2: in a fight D-pad Down is push to talk", (a, kind, mt))
W.execute('fightOff()')
ns['check'] = check
ns['clean'](W, "2")

# ================================================================ 3. Hub's buttons
STUB = r'''
SENT, POLESTAR_CALLS, HELD = {}, 0, {}
local Frame = {}
local function noop() end
Frame.__index = function(t, k) local v = rawget(Frame, k); if v ~= nil then return v end; return noop end
function Frame:SetScript(k, fn) self[k] = fn end
function Frame:RegisterForClicks(...) self.clicks = { ... } end
function CreateFrame(kind, name) local f = setmetatable({ kind = kind, name = name }, Frame); if name then _G[name] = f end; return f end
UIParent = CreateFrame("Frame", "UIParent")
function hooksecurefunc() end
SlashCmdList = {}
COMBAT = false
function InCombatLockdown() return COMBAT end
function GetTime() return 100 end
function GetCurrentKeyBoardFocus() return nil end
C_Timer = { After = function() end, NewTicker = function() end }
local function step() POLESTAR_CALLS = POLESTAR_CALLS + 1 end
Polestar = { Engine = { Next = step, Prev = step, SkipCurrentStep = step } }
WowCompanion = {
    S = function(fn) local ok, v = pcall(fn); if ok then return v end end,
    send = function(j) SENT[#SENT + 1] = j end, encode = function(t) return t end, push = function() end,
    pad = { op = function() return 1 end, describe = function() return {} end },
    snapshot = function() end,
    questGuests = { hold = function(g) HELD[#HELD + 1] = g end },
}
'''
H = lupa.LuaRuntime()
H.execute(STUB)
err = None
try:
    H.execute(HUB.replace("local ADDON = ...", "local ADDON = 'WowCompanionHub'", 1))
except Exception as e:
    err = str(e)[:300]
check(err is None, "3: Hub.lua loads under the stub", err or '')
G = H.globals()


def barcmds():
    return [G.SENT[i] for i in range(1, len(G.SENT) + 1) if G.SENT[i].type == "barcmd"]


def held():
    return [G.HELD[i] for i in range(1, len(G.HELD) + 1)]


for name, k, guest in (("WowCompanionPadTalk", "talk", ["chat"]), ("WowCompanionPadPolestarMenu", "psmenu", [])):
    b = G[name]
    check(b is not None and b.kind == "Button" and b.OnClick is not None, "3: %s is a plain button with a click" % name)
    if b is None or b.OnClick is None:
        continue
    for fight in (False, True):
        G.COMBAT = fight
        n0, h0 = len(barcmds()), len(held())
        b.OnClick(b, "LeftButton")
        got = barcmds()[n0:]
        check(len(got) == 1 and got[0].k == k, "3: %s click%s: one barcmd %s" % (name, " in a fight" if fight else "", k),
              [x.k for x in got])
        check(held()[h0:] == guest, "3: %s click%s holds %s in the same click" % (name, " in a fight" if fight else "",
                                                                                 guest or "no guest"), held()[h0:])
check(G.POLESTAR_CALLS == 0, "3: the buttons never call Polestar themselves (the app opens its menu)", G.POLESTAR_CALLS)
keys = set(re.findall(r'WowCompanion(?:Bar|Pad)\w+\s*=\s*"([a-z]+)"', HUB))
check({"talk", "psmenu"} <= keys, "3: BarCommands.hubKeys reads both keys off Hub.lua", sorted(keys))

print("\n%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
