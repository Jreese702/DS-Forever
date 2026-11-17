"""POLESTAR NEXT / PREVIOUS STEP ON A CONTROLLER BUTTON (PadMap m169, Hub hub57), off-device. The user, 2026-11-16: "The polestar
previous or next options should be able to be bound to keys in the controller bindings menus".

    python tools/test_polestar_bind.py

Proven here, on the REAL add-on files:
  1 the tags: PadMap m169, Hub hub57, each header note quoting the user; both compile as Lua 5.1;
  2 PadMap: two DS Forever commands, DSPSNEXT "Polestar: next step" / DSPSPREV "Polestar: previous step", listed with the others
    (right after Home Bar: the DS tab, keys "DS..."), each with the game's gold arrow (in the required harvest set) and the body
    "/click WowCompanionPadPolestarNext / Prev"; put on a button (WoW Defaults) and on a fight layer (In combat) as any DS command,
    and run in a fight; no spied call, no lockdown breach;
  3 Hub: the two plain buttons exist; a click sends ONE barcmd psnext / psprev (out of a fight and in one), and never calls
    Polestar itself (the app steps it through the Map room's one Polestar menu: Map.lua pg2 ops 24 / 25).
Needs lupa. Exit code 1 on any failure."""
import json
import os
import re
import sys

from lupa import lua51 as lupa

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ADDONS = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons")
PADMAP = open(os.path.join(ADDONS, "WowCompanionSpells", "PadMap.lua"), encoding="utf-8").read()
HUB = open(os.path.join(ADDONS, "WowCompanionHub", "Hub.lua"), encoding="utf-8").read()
ICONS = json.load(open(os.path.join(ROOT, "companion", "app", "src", "main", "assets", "harvest", "icons.json"), encoding="utf-8"))
QUOTE = '"The polestar previous or next options should be able to be bound to keys in the controller bindings menus"'

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
check('local PV = "m169"' in PADMAP and PADMAP.count(QUOTE) >= 1, "1: PadMap says m169, its notes quote the user")
check('local HV = "hub57"' in HUB and HUB.count(QUOTE) >= 1, "1: Hub says hub57, its notes quote the user")
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
check('DSPSNEXT' in keys and 'DSPSPREV' in keys and keys.index('DSPSNEXT') == keys.index('DSHOME') + 1
      and keys.index('DSPSPREV') == keys.index('DSPSNEXT') + 1,
      "2: Next / Previous step listed right after Home Bar (DS Forever's own: the DS tab)", keys[:9])
check(names.get('DSPSNEXT') == 'Polestar: next step' and names.get('DSPSPREV') == 'Polestar: previous step',
      "2: their names", (names.get('DSPSNEXT'), names.get('DSPSPREV')))
W.execute('WowCompanion.padMap.emit(true)')
cmds = {c['key']: c for c in ns['docs'](W, 'padmap')[-1]['m'].get('commands', [])}
nx, pv = cmds.get('DSPSNEXT') or {}, cmds.get('DSPSPREV') or {}
check(nx.get('b') == '/click WowCompanionPadPolestarNext' and pv.get('b') == '/click WowCompanionPadPolestarPrev',
      "2: the document's commands: each clicks its Hub button", (nx.get('b'), pv.get('b')))
check(nx.get('i') == 450908 and pv.get('i') == 450906 and {450908, 450906} <= set(ICONS['fids']),
      "2: the icons: the game's gold arrows right / left (in the required harvest set)", (nx.get('i'), pv.get('i')))
mn = re.findall(r'key = "DSPS(?:NEXT|PREV)", mn = "([^"]+)"', PADMAP)
check(len(mn) == 2 and all(len(m) <= 16 for m in mn), "2: their macro names fit the game's 16 characters", mn)

ok = W.eval('WowCompanion.padMap.putCommand(1, 1, 1, "DSPSNEXT")')
sl = ns['slot_doc'](W, 1, 1)
check(ok is True and sl.get('kind') == 'command' and sl.get('key') == 'DSPSNEXT' and sl.get('i') == 450908
      and sl.get('b') == '/click WowCompanionPadPolestarNext',
      "2: Next step put on the bare D-pad Left (WoW Defaults): the slot draws the command and its arrow", sl)
ok = W.eval('WowCompanion.padMap.putFight(1, 1, 2, "command", "DSPSPREV")')
check(ok is True and W.eval('HDR("wc-ft-2")') == "/click WowCompanionPadPolestarPrev",
      "2: Previous step on D-pad Up's In combat layer: the router's fight text clicks the Hub button", W.eval('HDR("wc-ft-2")'))
W.execute('fightOn()')
a, kind, mt = W.eval('key("PADDUP")')
check(kind == "slotfight" and mt == "/click WowCompanionPadPolestarPrev", "2: in a fight D-pad Up runs Previous step", (a, kind, mt))
W.execute('fightOff()')
ns['check'] = check
ns['clean'](W, "2")

# ================================================================ 3. Hub's buttons
STUB = r'''
SENT, POLESTAR_CALLS = {}, 0
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


for name, k in (("WowCompanionPadPolestarNext", "psnext"), ("WowCompanionPadPolestarPrev", "psprev")):
    b = G[name]
    check(b is not None and b.kind == "Button" and b.OnClick is not None, "3: %s is a plain button with a click" % name)
    if b is None or b.OnClick is None:
        continue
    for fight in (False, True):
        G.COMBAT = fight
        n0 = len(barcmds())
        b.OnClick(b, "LeftButton")
        got = barcmds()[n0:]
        check(len(got) == 1 and got[0].k == k, "3: %s click%s: one barcmd %s" % (name, " in a fight" if fight else "", k),
              [x.k for x in got])
check(G.POLESTAR_CALLS == 0, "3: the buttons never call Polestar themselves (the app steps it: Map.lua pg2)", G.POLESTAR_CALLS)
keys = set(re.findall(r'WowCompanion(?:Bar|Pad)\w+\s*=\s*"([a-z]+)"', HUB))
check({"psnext", "psprev"} <= keys, "3: BarCommands.hubKeys reads both keys off Hub.lua", sorted(keys))

print("\n%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
