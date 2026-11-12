"""The FIGHT-ONLY LAYER (PadMap m132 / m133, PadTabCtl p41), off-device: the REAL add-on files under test_party.py's stubbed game.

    python tools/test_fight_layer.py

The user's ruling, 2026-11-11: "Build a fight-only layer" - what the In combat tab sets applies ONLY in fights; the button's WoW
Defaults assignment (its out-of-combat spell / command) stays exactly as it is out of combat. Proven here:
  1 a command on In combat over a button with a WoW Defaults spell: out of combat the spell, in combat the command, back after;
  2 removal (Clear combat spell);
  3 a fight spell and a fight command never both sit on one button (the newer wins), and the In combat tab's box names only the
    fight-layer item it replaces (an empty fight layer: straight on);
  4 per character (another character sees none; a fresh load keeps it);
  5 an item / mount on the fight layer (their fight texts);
  6 a binding on the fight layer and a WoW Defaults binding under a fight command: the CHORD flips at the fight's start (before the
    lockdown, and in the header's own [combat] state snippet) and back at its end, with no forbidden write in combat;
  7 m133 (review): the chord follows the LIVE slot (page 2 with no entry: the WoW Defaults Jump) and B's give-back / the re-arm;
  8 m133: Party mode switched on mid-fight gives the chord to the router (its pad reads it), off again gives it back;
  10 m141 / p43 (the user, 2026-11-12, on 1429: "In combat still does not show all 5 tabs", then "All 5 on every button"): the base
    X / Y / A, a form's own set and the toolbar's bare D-pad take a command / item / mount on their fight layer (WoW Defaults untouched);
    a binding there is refused with its reason;
plus the documents (the combat view's `c.t` / `c.key`, no id) and no spied call. Needs lupa. Exit code 1 on any failure."""
import json
import os
import re
import sys

from lupa import lua51 as lupa

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADDONS = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons")
F = {
    "safety": os.path.join(ADDONS, "WowCompanion", "Safety.lua"),
    "tappad": os.path.join(ADDONS, "WowCompanion", "TapPad.lua"),
    "book": os.path.join(ADDONS, "WowCompanionSpells", "Book.lua"),
    "padmap": os.path.join(ADDONS, "WowCompanionSpells", "PadMap.lua"),
    "padtabctl": os.path.join(ADDONS, "WowCompanionSpells", "PadTabCtl.lua"),
}
SRC = {k: open(p, encoding="utf-8").read() for k, p in F.items()}
# the stubbed game is tools/wowstub.py's (moved out of test_party.py by combat-nav, shared with test_combat_pad.py); the world
# helpers are still test_party.py's own (one harness)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import GAME  # noqa: E402
PARTY = open(os.path.join(ROOT, "tools", "test_party.py"), encoding="utf-8").read()
WORLD_HELPERS = re.search(r"\nWORLD_HELPERS = r'''(.*?)'''", PARTY, re.S).group(1)
fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


# what this test adds to the stubbed game: the game's bar slots (storage = the bar's button ids, set 1 = 1-12, set 2 = 13-24 ...), the
# WoW Defaults spells on them (GetActionInfo), the attribute drivers ([combat] fight; idle) and the secure handle's own override
# bindings (self:SetBinding / SetBindingClick / ClearBinding: allowed in a fight only from secure code)
EXTRA = r'''
ACTIONINFO = {}
function GetActionInfo(s) local a = ACTIONINFO[s]; if a then return a[1], a[2] end end
function GetActionTexture(s) local a = ACTIONINFO[s]; return a and 130000 + a[2] or nil end
ADRV = {}
function RegisterAttributeDriver(f, attr, cond) ADRV[#ADRV + 1] = { f = f, a = attr, c = cond } end
function driveAll()
    for _, d in ipairs(ADRV) do
        for clause in (d.c .. ";"):gmatch("([^;]*);") do
            local r = SecureCmdOptionParse((clause:gsub("^%s+", "")))
            if r ~= nil and r ~= "" then d.f.a[d.a] = r break end
        end
    end
    driveStates()
end
local function hb(self, what) if COMBAT and not IN_SECURE then VIOL[#VIOL + 1] = what end end
function Frame:SetBinding(p, key, act) hb(self, "SetBinding"); BIND[key] = act; BOWNER[key] = self end
function Frame:SetBindingClick(p, key, name, mb) hb(self, "SetBindingClick"); BIND[key] = "CLICK " .. name .. ":" .. (mb or "LeftButton"); BOWNER[key] = self end
function Frame:ClearBinding(key) hb(self, "ClearBinding"); if BOWNER[key] == self then BIND[key] = nil; BOWNER[key] = nil end end
C_SpellBook = { GetSpellBookItemInfo = function(slot) local id = BOOK[slot]; return id and { spellID = id } or nil end }
C_Item = { GetItemNameByID = function(id) if id == 6948 then return "Hearthstone" end end, GetItemIconByID = function() return 134414 end }
SPELLNAME[23229] = "Swift Brown Steed"
C_MountJournal = { GetMountInfoByID = function(id) if id == 7 then return "Swift Brown Steed", 23229, 132261 end end,
                   GetMountIDs = function() return {} end, GetNumDisplayedMounts = function() return 0 end }
'''
AFTER = r'''
WowCompanion.spellsStorageFor = function(page, set, pos) return (page - 1) * 48 + (set - 1) * 12 + pos end
ACTIONINFO[1] = { "spell", 331 }               -- WoW Defaults: Healing Wave on the bare D-pad Left
ACTIONINFO[2] = { "spell", 324 }               -- Lightning Shield on the bare D-pad Up
ACTIONINFO[13] = { "spell", 403 }              -- Lightning Bolt on ZL + Left (a WoW Defaults binding goes over it below)
local ctl = WowCompanion._spellsBook.ctl
ctl.croom, ctl.ctrlTab, ctl.onTabs = true, true, true
function pp(key) return WowCompanion.padTabCtl.press(key) end
function FT(slot) local t = (WowCompanionSpellsDB.fightBy or {})[WHO .. "-" .. REALM]; return t and t[slot] end
function HDR(k) return rawget(_G, "WowCompanionFaceRouter").a[k] end
function fightOn() fire("PLAYER_REGEN_DISABLED"); COMBAT = true; driveAll() end
function fightOff() COMBAT = false; driveAll(); fire("PLAYER_REGEN_ENABLED"); runTimers(NOW + 1) end
-- a hardware press of a key (its binding), both edges; the router's wc-kind / macrotext after the key-down
function key(k)
    press(k, true)
    local a = BIND[k]
    local name = a and a:match("^CLICK ([%w_]+):")
    local b = name and rawget(_G, name)
    local kind, mt = b and b.a["wc-kind"], b and b.a["macrotext"]
    press(k, false)
    return a, kind, mt
end
'''


def boot(pre=''):
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(GAME)
    L.execute(EXTRA)
    if pre:
        L.execute(pre)
    load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    for k in ("safety", "tappad", "book", "padmap", "padtabctl"):
        load(SRC[k], '@' + os.path.basename(F[k]))(os.path.basename(F[k]))
    L.execute(WORLD_HELPERS)
    L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
    L.execute(AFTER)
    L.execute('WowCompanion.padMap.emit(true); runTimers(NOW + 1)')
    return L


def docs(L, typ):
    out = []
    for i in range(1, int(L.eval('#SENT')) + 1):
        try:
            d = json.loads(L.eval('SENT[%d]' % i))
        except Exception:
            continue
        if isinstance(d, dict) and d.get('type') == typ:
            out.append(d)
    return out


def slot_doc(L, set_, pos):
    L.execute('WowCompanion.padMap.emit(true)')
    m = docs(L, 'padmap')[-1]['m']
    return m['sets'][set_ - 1]['slots'][pos - 1]


def ft(L, slot):
    e = L.eval('FT(%d)' % slot)
    return dict(e) if e is not None else None


def clean(L, name):
    check(L.eval('#SPY') == 0 and L.eval('#VIOL') == 0 and L.eval('#SNIPERR') == 0 and list(L.eval('ERRS').values()) == [],
          name + ": no spied call (no trace), no lockdown breach, no snippet error",
          (L.eval('SPY[1]'), L.eval('VIOL[1]'), L.eval('SNIPERR[1]'), list(L.eval('ERRS').values())[:2]))


comp = lupa.LuaRuntime().eval('function(s, n) local f, e = loadstring(s, n); return e end')
for k in ("padmap", "padtabctl"):
    e = comp(SRC[k], k)
    check(e is None, "Lua 5.1 compiles %s (200 locals, 60 upvalues)" % os.path.basename(F[k]), e or '')
check('local PV = "m141"' in SRC["padmap"] and 'local CV = "p43"' in SRC["padtabctl"], "the versions: PadMap m141, PadTabCtl p43 (ctl-all5)")
ONF = re.search(r'local ONFIGHT = \[=\[(.*?)\]=\]', SRC["padmap"], re.S).group(1)
check(not re.search(r'\bfunction\b', ONF), "the header's ONFIGHT snippet holds no 'function' (the game refuses it)")
FBS = re.search(r'function OV\.fbSnip\(fbf\)\n    return .*?\[=\[(.*?)\]=\]', SRC["padmap"], re.S).group(1)
check(not re.search(r'\bfunction\b', FBS) and 'h:SetBinding(true' in FBS,
      "the chord flip body (OV.fbSnip) holds no 'function'; the header's own bindings")

# ================================================================ 1. a command on In combat over a WoW Defaults spell
W = boot()
check(list(W.eval('ERRS').values()) == [] and W.eval('#SNIPERR') == 0, "the world loads", list(W.eval('ERRS').values())[:2])
sl = slot_doc(W, 1, 1)
check(sl['kind'] == 'spell' and sl.get('id') == 331, "WoW Defaults: Healing Wave on the bare D-pad Left", sl)
ok = W.eval('WowCompanion.padMap.putFight(1, 1, 1, "command", "DSMAP")')
e = ft(W, 1)
check(ok is True and e and e.get('t') == 'command' and e.get('key') == 'DSMAP' and e.get('n') == 'Open Map',
      "putFight: Open Map on the fight layer of slot 1 (this character's fightBy, kind command)", e)
check(W.eval('ACTIONINFO[1][2]') == 331 and W.eval('#SPY') == 0, "the button itself is untouched (Healing Wave still on it, no bar write)")
check(W.eval('HDR("wc-ft-1")') == "/click WowCompanionPadMap", "the router's fight text for slot 1 is the command's (pre-armed out of combat)",
      W.eval('HDR("wc-ft-1")'))
sl = slot_doc(W, 1, 1)
check(sl['kind'] == 'combat' and sl.get('ov') is True and sl['c'].get('t') == 'command' and sl['c'].get('key') == 'DSMAP'
      and 'id' not in sl['c'] and sl['c'].get('n') == 'Open Map' and sl.get('o', {}).get('id') == 331,
      "the document: a combat view, c = Open Map (t command, key, no id), o = Healing Wave (WoW Defaults)", sl)
# out of combat: the router (if it holds the key) gives the game's own button, Healing Wave
W.execute('hit(1)')
k = W.eval('router(1).a["wc-kind"]')
check(k in ("native", None) or k == "roombar", "out of combat: D-pad Left is the game's own button (Healing Wave), never the command", k)
check(W.eval('router(1).a["wc-kind"]') != "slotfight", "out of combat: no fight text")
W.execute('fightOn()')
a, kind, mt = W.eval('key("PADDLEFT")')
check(a == "CLICK WowCompanionFaceD1:LeftButton" and kind == "slotfight" and mt == "/click WowCompanionPadMap",
      "in combat: D-pad Left runs Open Map (the fight layer's command)", (a, kind, mt))
W.execute('fightOff()')
a, kind, mt = W.eval('key("PADDLEFT")')
check(kind != "slotfight" and (mt or "").find("WowCompanionPadMap") < 0,
      "after the fight: D-pad Left is Healing Wave's button again (no command)", (a, kind, mt))
check(W.eval('ACTIONINFO[1][2]') == 331, "the WoW Defaults spell never moved")
clean(W, "1")

# ================================================================ 2. removal
ok = W.eval('WowCompanion.padMap.clearCombat(1, 1, 1)')
check(ok is True and ft(W, 1) is None and W.eval('HDR("wc-ft-1")') is None, "Clear combat spell: the fight-layer command is gone (store + header)")
W.execute('fightOn()')
a, kind, mt = W.eval('key("PADDLEFT")')
check(kind != "slotfight", "in combat after the removal: D-pad Left is Healing Wave (the game's button)", (a, kind, mt))
W.execute('fightOff()')
sl = slot_doc(W, 1, 1)
check(sl['kind'] == 'spell' and sl.get('id') == 331, "the document: Healing Wave alone again", sl)
clean(W, "2")

# ================================================================ 3. a fight spell and a fight command never share a button (the newer wins)
ok = W.eval('WowCompanion.padMap.putCombat(1, 1, 1, 1)')                   # book slot 1 = Lightning Bolt (403)
check(ok is True and ft(W, 1).get('n') == 'Lightning Bolt' and ft(W, 1).get('t') is None, "a fight spell first: Lightning Bolt", ft(W, 1))
W.execute('WowCompanion.padMap.putFight(1, 1, 1, "command", "DSMAP")')
check(ft(W, 1).get('t') == 'command' and 'id' not in ft(W, 1) and W.eval('HDR("wc-ft-1")') == "/click WowCompanionPadMap",
      "a fight command over it: the command wins, the spell is gone", ft(W, 1))
W.execute('WowCompanion.padMap.putCombat(1, 1, 1, 1)')
check(ft(W, 1).get('n') == 'Lightning Bolt' and ft(W, 1).get('t') is None and W.eval('HDR("wc-ft-1")') == "/cast Lightning Bolt",
      "a fight spell over the command: the spell wins", (ft(W, 1), W.eval('HDR("wc-ft-1")')))
# the In combat tab: the box names only the fight-layer item it replaces
W.execute('WowCompanion.padTabCtl.enter("buttons"); pp("PADRSHOULDER")')


def ctl(L):
    return docs(L, 'padtabctl')[-1]['c']


c = ctl(W)
for _ in range(12):
    if c['view'] == 'combat' and c['pos'] == 1 and c['shown'] == 1:
        break
    W.execute('pp("PADDLEFT")')
    c = ctl(W)
check(c['view'] == 'combat' and c['pos'] == 1, "the In combat tab, the bare D-pad Left", c)
W.execute('pp("PAD1"); for i = 1, 4 do pp("PADDRIGHT") end')
check(ctl(W).get('chooser', {}).get('list') == 'dstool', "its chooser's DS tab", ctl(W).get('chooser'))
for _ in range(40):
    if ctl(W)['chooser']['id'] == 'DSCHAT':
        break
    W.execute('pp("PADDDOWN")')
W.execute('pp("PAD1")')
b = ctl(W).get('box')
check(b and b['kind'] == 'replace' and b.get('what') == 'DSCHAT' and ft(W, 1).get('n') == 'Lightning Bolt',
      "Open Chat over the fight spell: Replace asks first (the fight layer's Lightning Bolt), nothing changed yet", b)
W.execute('pp("PAD1")')
check(ft(W, 1).get('t') == 'command' and ft(W, 1).get('key') == 'DSCHAT' and not ctl(W).get('box') and W.eval('ACTIONINFO[1][2]') == 331,
      "Replace's one yes: Open Chat on the fight layer; Healing Wave untouched out of combat", (ft(W, 1), ctl(W).get('box')))
# an empty fight layer: straight on (no box)
W.execute('WowCompanion.padMap.clearCombat(1, 1, 1)')
W.execute('pp("PAD1"); for i = 1, 4 do pp("PADDRIGHT") end')
for _ in range(40):
    if ctl(W)['chooser']['id'] == 'DSMAP':
        break
    W.execute('pp("PADDDOWN")')
W.execute('pp("PAD1")')
check(not ctl(W).get('box') and ft(W, 1).get('key') == 'DSMAP', "an empty fight layer: Open Map goes straight on (no box)", (ctl(W).get('box'), ft(W, 1)))
# Clear combat spell from the chooser's last row
W.execute('pp("PAD1"); for i = 1, 4 do pp("PADDRIGHT") end; for i = 1, 80 do pp("PADDDOWN") end')
check(ctl(W)['chooser']['id'] == 'uncombat', "the chooser's last row: Clear combat spell", ctl(W)['chooser'])
W.execute('pp("PAD1")')
if ctl(W).get('box'):
    W.execute('pp("PAD1")')
check(ft(W, 1) is None and W.eval('ACTIONINFO[1][2]') == 331, "Clear combat spell removes the fight-layer command; Healing Wave stays", ft(W, 1))
clean(W, "3")

# ================================================================ 4. per character
W.execute('WowCompanion.padMap.putFight(1, 1, 1, "command", "DSMAP")')
k = W.eval('WHO .. "-" .. REALM')
check(W.eval('WowCompanionSpellsDB.fightBy["%s"][1].key' % k) == 'DSMAP', "kept under this character's name-realm (%s)" % k)
W.execute('WHO = "Alt"')
sl = slot_doc(W, 1, 1)
check(sl['kind'] == 'spell' and ft(W, 1) is None, "another character: no fight layer on that button", sl)
W.execute('WHO = "Korvash"')
check(slot_doc(W, 1, 1)['kind'] == 'combat', "back on Korvash: the fight layer is there")
saved = W.eval('WowCompanionSpellsDB.fightBy["%s"][1]' % k)
pre = ('WowCompanionSpellsDB = { fightBy = { ["%s"] = { [1] = { t = "command", key = "DSMAP", n = "Open Map", i = %d, page = 1, set = 1, pos = 1 } } } }'
       % (k, int(saved.i)))
W2 = boot(pre)
W2.execute('WowCompanion.padMap.putFight(1, 1, 2, "command", "DSCHAT"); WowCompanion.padMap.clearCombat(1, 1, 2)')   # any change re-arms
check(W2.eval('HDR("wc-ft-1")') == "/click WowCompanionPadMap" and slot_doc(W2, 1, 1)['c'].get('key') == 'DSMAP',
      "a fresh load (the saved variables): the fight layer's command is armed again", W2.eval('HDR("wc-ft-1")'))
W2.execute('fightOn()')
a, kind, mt = W2.eval('key("PADDLEFT")')
check(kind == "slotfight" and mt == "/click WowCompanionPadMap", "... and runs in the next fight", (kind, mt))
W2.execute('fightOff()')
clean(W2, "4")

# ================================================================ 5. an item / a mount on the fight layer
ok = W.eval('WowCompanion.padMap.putFight(1, 1, 3, "item", 6948)')
check(ok is True and ft(W, 3).get('t') == 'item' and W.eval('HDR("wc-ft-3")') == "/use item:6948", "an item: /use item:6948 in fights",
      W.eval('HDR("wc-ft-3")'))
check(slot_doc(W, 1, 3)['c'].get('key') == 'i:6948' and 'id' not in slot_doc(W, 1, 3)['c'], "its document key i:6948 (no id)", slot_doc(W, 1, 3)['c'])
ok = W.eval('WowCompanion.padMap.putFight(1, 1, 4, "mount", 7)')
check(ok is True and ft(W, 4).get('t') == 'mount' and W.eval('HDR("wc-ft-4")') == "/cast Swift Brown Steed",
      "a mount: its summon spell in fights", W.eval('HDR("wc-ft-4")'))
check(W.eval('WowCompanion.padMap.putFight(1, 1, 4, "mount", 999)')[0] is False and ft(W, 4).get('t') == 'mount', "an unknown mount is refused (nothing changes)")
check(W.eval('WowCompanion.padMap.putFight(1, 1, 7, "command", "DSMAP")')[0] is False, "B (the game's Back) takes no fight-layer entry (m141)")
fs = list(W.eval('WowCompanion.padMapFightSpells()').values())
check(6948 not in fs and 7 not in fs, "the In-combat spell list (state pictures by spell id) leaves the item / mount out", fs)
W.execute('WowCompanion.padMap.clearCombat(1, 1, 3); WowCompanion.padMap.clearCombat(1, 1, 4)')
check(W.eval('#SPY') == 0, "no bar write for items / mounts (the button untouched)")
clean(W, "5")

# ================================================================ 6. bindings: the chord flips
ok = W.eval('WowCompanion.padMap.putFight(1, 1, 2, "bind", "JUMP")')
check(ok is True and ft(W, 2).get('t') == 'bind' and W.eval('HDR("wc-ft-2")') is None, "Jump on D-pad Up's fight layer (no fight text: a chord)", ft(W, 2))
out0 = W.eval('BIND["PADDUP"]')
check(out0 != "JUMP", "out of combat D-pad Up is not Jump (Lightning Shield's button / the game's)", out0)
check(W.eval('HDR("wc-fb-n")') >= 1 and W.eval('HDR("wc-fb-k-1")') == "PADDUP" and W.eval('HDR("wc-fbs-2")') == "JUMP"
      and W.eval('HDR("wc-fb-pc-1")') == "WowCompanionFaceD2",
      "armed out of combat on the header: chord PADDUP (router D2), slot 2's fight-layer binding JUMP",
      (W.eval('HDR("wc-fb-n")'), W.eval('HDR("wc-fb-k-1")'), W.eval('HDR("wc-fbs-2")')))
W.execute('fire("PLAYER_REGEN_DISABLED")')
check(W.eval('BIND["PADDUP"]') == "JUMP", "the fight's start (before the lockdown): D-pad Up is Jump", W.eval('BIND["PADDUP"]'))
W.execute('COMBAT = true; driveAll()')
check(W.eval('BIND["PADDUP"]') == "JUMP", "in the lockdown, the header's [combat] snippet keeps it Jump", W.eval('BIND["PADDUP"]'))
W.execute('COMBAT = false; driveAll()')                                   # the driver flips back before the event
check(W.eval('BIND["PADDUP"]') == out0, "the [combat] driver's end: the snippet gives D-pad Up its out-of-combat side back", W.eval('BIND["PADDUP"]'))
W.execute('fire("PLAYER_REGEN_ENABLED"); runTimers(NOW + 1)')
check(W.eval('BIND["PADDUP"]') != "JUMP", "after the fight: D-pad Up is not Jump", W.eval('BIND["PADDUP"]'))
# the snippet alone (a fight whose start the event missed: a reload): the chord still flips, from secure code
W.execute('COMBAT = true; driveAll()')
check(W.eval('BIND["PADDUP"]') == "JUMP" and W.eval('#VIOL') == 0, "the [combat] snippet alone flips the chord in a fight (secure: no breach)",
      (W.eval('BIND["PADDUP"]'), W.eval('VIOL[1]')))
W.execute('COMBAT = false; driveAll(); fire("PLAYER_REGEN_ENABLED"); runTimers(NOW + 1)')
W.execute('WowCompanion.padMap.clearCombat(1, 1, 2)')
check(W.eval('HDR("wc-fb-n")') == 0 and W.eval('HDR("wc-fb-k-1")') is None, "removed: nothing armed on the header", W.eval('HDR("wc-fb-n")'))
W.execute('fightOn()')
check(W.eval('BIND["PADDUP"]') != "JUMP", "removed: D-pad Up is not Jump in the next fight", W.eval('BIND["PADDUP"]'))
W.execute('fightOff()')
# a WoW Defaults binding (ZL + Left = Jump) under a fight-layer command: in fights its chord goes to the router, which runs the command
ok = W.eval('WowCompanion.padMap.putBind(1, 2, 1, "JUMP")')
check(ok is True and W.eval('BIND["SHIFT-PADDLEFT"]') == "JUMP", "WoW Defaults: Jump on ZL + Left", W.eval('BIND["SHIFT-PADDLEFT"]'))
ok = W.eval('WowCompanion.padMap.putFight(1, 2, 1, "command", "DSMAP")')
check(ok is True and W.eval('BIND["SHIFT-PADDLEFT"]') == "JUMP", "Open Map on its fight layer: out of combat ZL + Left is still Jump")
sl = slot_doc(W, 2, 1)
check(sl['kind'] == 'combat' and sl['c'].get('key') == 'DSMAP' and sl.get('o', {}).get('n') in ('Jump', 'JUMP'),
      "the document: In combat Open Map, Otherwise the binding", sl)
W.execute('fightOn(); trig(true, false)')
a, kind, mt = W.eval('key("SHIFT-PADDLEFT")')
check(a == "CLICK WowCompanionFaceD1:LeftButton" and kind == "slotfight" and mt == "/click WowCompanionPadMap",
      "in combat ZL + Left runs Open Map (the chord on the router, the left bar's slot 13 fight text)", (a, kind, mt))
W.execute('trig(false, false); fightOff()')
check(W.eval('BIND["SHIFT-PADDLEFT"]') == "JUMP", "after the fight: ZL + Left is Jump again", W.eval('BIND["SHIFT-PADDLEFT"]'))
# a fight SPELL over a WoW Defaults binding is allowed now too (it was refused: m43's "bind")
ok = W.eval('WowCompanion.padMap.putCombat(1, 2, 1, 1)')
check(ok is True and W.eval('HDR("wc-ft-13")') == "/cast Lightning Bolt", "a fight spell over a WoW Defaults binding: allowed, armed", W.eval('HDR("wc-ft-13")'))
W.execute('fightOn(); trig(true, false)')
a, kind, mt = W.eval('key("SHIFT-PADDLEFT")')
check(kind == "slotfight" and mt == "/cast Lightning Bolt", "... and cast in fights on ZL + Left", (kind, mt))
W.execute('trig(false, false); fightOff()')
clean(W, "6")

# ================================================================ 7. m133 (review of m132): the live page, B's give-back
# ZL + Left: WoW Defaults Jump, Lightning Bolt on page 1's slot 13 fight layer (above). Page 2 (slot 61) holds no fight-layer entry
LBTN = 'rawget(_G, "WowCompanionFaceD1").refs["wc-btn-left-1"]'
check(W.eval(LBTN + '._id') == 13, "router D1's left bar button: slot 13 (page 1)", W.eval(LBTN + '._id'))
W.execute(LBTN + '._id = 61')                                             # the player pages to page 2
W.execute('fightOn(); trig(true, false)')
check(W.eval('BIND["SHIFT-PADDLEFT"]') == "JUMP" and W.eval('#VIOL') == 0,
      "page 2 in a fight (no fight-layer entry there): ZL + Left is the WoW Defaults Jump, never the action button it hides",
      W.eval('BIND["SHIFT-PADDLEFT"]'))
W.execute(LBTN + '._id = 13; trig(false, false); hit(3)')                 # back to page 1; any router press re-checks
check(W.eval('BIND["SHIFT-PADDLEFT"]') == "CLICK WowCompanionFaceD1:LeftButton",
      "paged back to page 1 (the next router press re-checks): ZL + Left is the router's (Lightning Bolt)", W.eval('BIND["SHIFT-PADDLEFT"]'))
W.execute('trig(true, false)')
a, kind, mt = W.eval('key("SHIFT-PADDLEFT")')
check(kind == "slotfight" and mt == "/cast Lightning Bolt", "... and casts the fight spell", (kind, mt))
W.execute(LBTN + '._id = 61; trig(false, false); hit(3); trig(true, false)')
a, kind, mt = W.eval('key("SHIFT-PADDLEFT")')
check(a == "JUMP" and W.eval('#VIOL') == 0, "page 2 again mid-fight (after a router press): ZL + Left is Jump", (a, kind, mt))
W.execute(LBTN + '._id = 13; trig(false, false); hit(3)')
# B's give-back in the fight: the chord goes back to the binding; an attack's re-arm takes the fight layer back
W.execute('hit(7)')
check(W.eval('router(1).a["wc-given"]') is True and W.eval('BIND["SHIFT-PADDLEFT"]') == "JUMP" and W.eval('#VIOL') == 0,
      "B pressed in a fight (given back): ZL + Left is the WoW Defaults Jump (secure, no breach)", W.eval('BIND["SHIFT-PADDLEFT"]'))
W.execute('clickName("WowCompanionFaceRearm", true); clickName("WowCompanionFaceRearm", false)')
check(W.eval('router(1).a["wc-given"]') is None and W.eval('BIND["SHIFT-PADDLEFT"]') == "CLICK WowCompanionFaceD1:LeftButton",
      "re-armed (an attack): ZL + Left is the router's fight spell again", W.eval('BIND["SHIFT-PADDLEFT"]'))
W.execute('clickName("WowCompanionFaceGiveBack", true); clickName("WowCompanionFaceGiveBack", false)')
check(W.eval('BIND["SHIFT-PADDLEFT"]') == "JUMP", "the give-back button (Book's cell) too: ZL + Left is Jump", W.eval('BIND["SHIFT-PADDLEFT"]'))
W.execute('fightOff()')
check(W.eval('BIND["SHIFT-PADDLEFT"]') == "JUMP", "after the fight: ZL + Left is Jump", W.eval('BIND["SHIFT-PADDLEFT"]'))
# a fight-layer binding knows its page now: Jump on page 1's D-pad Up only
W.execute('WowCompanion.padMap.putFight(1, 1, 2, "bind", "JUMP")')
UBTN = 'rawget(_G, "WowCompanionFaceD2").refs["wc-btn-top-1"]'
W.execute(UBTN + '._id = 50; fightOn()')                                 # page 2's D-pad Up (slot 50): nothing on its fight layer
check(W.eval('BIND["PADDUP"]') == "CLICK WowCompanionFaceD2:LeftButton",
      "page 2 in a fight: D-pad Up is the router's (its own button), not page 1's fight-layer Jump", W.eval('BIND["PADDUP"]'))
W.execute(UBTN + '._id = 2; hit(3)')
check(W.eval('BIND["PADDUP"]') == "JUMP", "page 1: D-pad Up is the fight layer's Jump", W.eval('BIND["PADDUP"]'))
W.execute('hit(7)')
check(W.eval('BIND["PADDUP"]') == "CLICK WowCompanionFaceD2:LeftButton", "B's give-back: D-pad Up is the router's again (Lightning Shield's button)",
      W.eval('BIND["PADDUP"]'))
W.execute('fightOff(); WowCompanion.padMap.clearCombat(1, 1, 2)')
clean(W, "7")

# ================================================================ 8. m133: Party mode switched on mid-fight (the m77 rule: its pad reads the router)
W3 = boot('WowCompanion = WowCompanion or {}; WowCompanion.partyOk = true')
W3.execute('UNITS.party1 = { name = "Ana", cls = "PRIEST", alive = true }')
ok = W3.eval('WowCompanion.padMap.putFight(1, 1, 2, "bind", "JUMP")')
check(ok is True, "Jump on D-pad Up's fight layer (a Party-mode world)")
W3.execute('fightOn()')
check(W3.eval('BIND["PADDUP"]') == "JUMP", "in the fight, Party mode off: D-pad Up is Jump", W3.eval('BIND["PADDUP"]'))
W3.execute('clickName("WowCompanionPartyOn", true); clickName("WowCompanionPartyOn", false)')
check(W3.eval('PADA("wc-party")') is True and W3.eval('BIND["PADDUP"]') == "CLICK WowCompanionFaceD2:LeftButton" and W3.eval('#VIOL') == 0,
      "Party mode switched on mid-fight: D-pad Up goes to the router (its pad reads it), from secure code, no breach",
      (W3.eval('PADA("wc-party")'), W3.eval('BIND["PADDUP"]'), W3.eval('VIOL[1]')))
a, kind, mt = W3.eval('key("PADDUP")')
check(a == "CLICK WowCompanionFaceD2:LeftButton" and "WowCompanionPartyPad" in (mt or ""),
      "... the press reaches the party pad (the pick can move)", (a, kind, mt))
W3.execute('clickName("WowCompanionPartyOff", true); clickName("WowCompanionPartyOff", false)')
check(W3.eval('PADA("wc-party")') is None and W3.eval('BIND["PADDUP"]') == "JUMP", "Party mode off again mid-fight: D-pad Up is Jump again",
      W3.eval('BIND["PADDUP"]'))
W3.execute('clickName("WowCompanionPartyOn", true); clickName("WowCompanionPartyOn", false); fightOff()')
check(W3.eval('BIND["PADDUP"]') != "JUMP", "after the fight (Party mode on): D-pad Up is not Jump", W3.eval('BIND["PADDUP"]'))
W3.execute('fightOn()')
check(W3.eval('BIND["PADDUP"]') == "CLICK WowCompanionFaceD2:LeftButton", "a fight that starts in Party mode: D-pad Up is the router's",
      W3.eval('BIND["PADDUP"]'))
W3.execute('fightOff()')
clean(W3, "8")

# ================================================================ 9. integration (int/1429): the fight layer x combat-nav's pull rule (PadMap m134
# of combat-nav: a fight's start carries every key whatever is open; only the game menu keeps the router aside). Both branches changed
# routerBind; the combined behaviour: a loot window at the pull no longer holds the fight layer's chord off, the game menu still does
W4 = boot()
ok = W4.eval('WowCompanion.padMap.putFight(1, 1, 2, "bind", "JUMP")')
check(ok is True, "9: Jump on D-pad Up's fight layer")
W4.execute('LootFrame = { IsShown = function() return true end }; runTimers(NOW + 1); fightOn()')
check(W4.eval('BIND["PADDUP"]') == "JUMP" and W4.eval('#VIOL') == 0,
      "9: a loot window open at the pull: the keys are carried and the fight layer's chord flips (D-pad Up is Jump)", W4.eval('BIND["PADDUP"]'))
W4.execute('LootFrame = nil; fightOff()')
check(W4.eval('BIND["PADDUP"]') != "JUMP", "9: after the fight: D-pad Up is not Jump", W4.eval('BIND["PADDUP"]'))
W4.execute('GameMenuFrame = { IsShown = function() return true end }; NOW = NOW + 2; runTimers(NOW + 1); fire("PLAYER_REGEN_DISABLED")')
check(W4.eval('BIND["PADDUP"]') != "JUMP",
      "9: the game menu open at the pull (the router aside for the fight): the fight layer's chord is NOT flipped at the start",
      W4.eval('BIND["PADDUP"]'))
W4.execute('COMBAT = true; driveAll(); COMBAT = false; driveAll(); GameMenuFrame = nil; fire("PLAYER_REGEN_ENABLED"); runTimers(NOW + 1)')
clean(W4, "9")

# ================================================================ 10. m141 / p43: the fight layer on EVERY In combat button (the user,
# 2026-11-12, on 1429: "In combat still does not show all 5 tabs", then the pick "All 5 on every button")
FORMS = r"""
GetNumShapeshiftForms = function() return 1 end
GetShapeshiftFormInfo = function(x) if x == 1 then return 132276, false, true, 768 end end
SPELLNAME[768] = "Cat Form"
"""
W5 = boot(FORMS)
pm = 'WowCompanion.padMap'
FACEDB = 'WowCompanionSpellsDB.faceBy[WHO .. "-" .. REALM]'
# a base A (set 1, position 8): Open Map on its fight layer; out of combat A stays the game's Jump (fixed, nothing of ours bound)
ok = W5.eval(pm + '.putFight(1, 1, 8, "command", "DSMAP")')
fe = W5.eval(FACEDB + '[8]')
check(ok is True and fe is not None and fe.t == 'command' and fe.key == 'DSMAP', "10: Open Map on base A's fight layer (the face store, `t` command)",
      dict(fe) if fe is not None else None)
sl = slot_doc(W5, 1, 8)
check(sl['kind'] == 'fixed' and sl.get('cf', {}).get('t') == 'command' and sl['cf'].get('key') == 'DSMAP' and 'id' not in sl['cf'],
      "10: the document: A is still the game's own (fixed) with the fight layer's Open Map as its In combat entry (no id)", sl)
check(W5.eval('router(8).a["wc-fight-type"]') == "macro" and W5.eval('router(8).a["wc-fight-macro"]') == "/click WowCompanionPadMap",
      "10: the router's A: its fight action is the command's text", (W5.eval('router(8).a["wc-fight-type"]'), W5.eval('router(8).a["wc-fight-macro"]')))
out0 = W5.eval('BIND["PAD1"]')
a, kind, mt = W5.eval('key("PAD1")')
check(kind != "fight" and 'WowCompanionPadMap' not in (mt or ''), "10: out of combat A is the game's own (Jump: never the command)", (a, kind, mt))
W5.execute('fightOn()')
a, kind, mt = W5.eval('key("PAD1")')
check(a == "CLICK WowCompanionCombatFace8:LeftButton" and kind == "fight" and mt == "/click WowCompanionPadMap",
      "10: in a fight A runs Open Map (the router's face button, its fight action)", (a, kind, mt))
W5.execute('fightOff()')
a, kind, mt = W5.eval('key("PAD1")')
check(kind != "fight" and 'WowCompanionPadMap' not in (mt or ''), "10: after the fight A is the game's own again", (a, kind, mt))
fs = list(W5.eval('WowCompanion.padMapFightSpells()').values())
check(fs == [], "10: the state list leaves A's command out (no spell)", fs)
# an item on base X, a mount on base Y
ok = W5.eval(pm + '.putFight(1, 1, 5, "item", 6948)')
check(ok is True and W5.eval('router(5).a["wc-fight-macro"]') == "/use item:6948", "10: an item on base X's fight layer", W5.eval('router(5).a["wc-fight-macro"]'))
ok = W5.eval(pm + '.putFight(1, 1, 6, "mount", 7)')
check(ok is True and W5.eval('router(6).a["wc-fight-macro"]') == "/cast Swift Brown Steed", "10: a mount on base Y's fight layer",
      W5.eval('router(6).a["wc-fight-macro"]'))
r = W5.eval(pm + '.putFight(1, 1, 8, "bind", "JUMP")')
check(r[0] is False and r[1] == 'fixed' and W5.eval(FACEDB + '[8].key') == 'DSMAP',
      "10: a binding on base A is refused (fixed: the game keeps the key), nothing changes", tuple(r))
# a fight spell there replaces it (one fight-layer entry per button), and Clear takes it off
W5.execute('BOOK = { 403 }')
ok = W5.eval(pm + '.putCombatFace(8, 1)')
check(ok is True and W5.eval(FACEDB + '[8].t') is None and W5.eval('router(8).a["wc-fight-spell"]') == "Lightning Bolt",
      "10: a fight spell on A replaces the command (the newer wins)", W5.eval('router(8).a["wc-fight-spell"]'))
# hub43 pin (the user, 2026-11-12, a Hunter: "sometimes when using arcane shot my auto fire just stops and my dude stands there"): a HARMFUL
# fight spell runs PadMap's attack-on-cast lines first - Hub.lua's Combat Auto Shot reads that /startattack's attack start as the ability's
ATTACK_LINES = "/target [noexists][dead] softenemy\n/startattack [harm,nodead]\n/click [harm,nodead] WowCompanionFaceRearm\n"
W5.execute('HARM_SAVE = C_Spell.IsSpellHarmful; C_Spell.IsSpellHarmful = function(s) return s == 403 or s == "Lightning Bolt" end')
ok = W5.eval(pm + '.putCombatFace(8, 1)')
check(ok is True and W5.eval('router(8).a["wc-fight-type"]') == "macro"
      and W5.eval('router(8).a["wc-fight-macro"]') == ATTACK_LINES + "/cast Lightning Bolt",
      "hub43 pin: a harmful fight spell's text = PadMap's attackLines('') + /cast", W5.eval('router(8).a["wc-fight-macro"]'))
W5.execute('C_Spell.IsSpellHarmful = HARM_SAVE; ' + pm + '.putCombatFace(8, 1)')
W5.execute(pm + '.putFight(1, 1, 8, "command", "DSMAP"); ' + pm + '.clearCombatFace(8)')
check(W5.eval(FACEDB + '[8]') is None and W5.eval('router(8).a["wc-fight-type"]') is None,
      "10: Clear combat spell takes A's fight-layer command off", W5.eval('router(8).a["wc-fight-type"]'))
# a form's own set (Cat Form, A): its fight text "wc-ff-<form index>-<pos>"
ok = W5.eval(pm + '.putFight(1, 1, 8, "command", "DSMAP", 768)')
ffe = W5.eval('WowCompanionSpellsDB.formFightBy[WHO .. "-" .. REALM][768][8]')
check(ok is True and ffe is not None and ffe.t == 'command' and W5.eval('HDR("wc-ff-1-8")') == "/click WowCompanionPadMap",
      "10: Open Map on Cat Form's A (the form's own set): its fight text", W5.eval('HDR("wc-ff-1-8")'))
W5.execute(pm + '.emit(true)')
ffd = [d for d in (docs(W5, 'padmap')[-1]['m'].get('ff') or []) if d['f'] == 768 and d['p'] == 8]
check(ffd and ffd[0].get('t') == 'command' and ffd[0].get('key') == 'DSMAP' and 'id' not in ffd[0], "10: the document's ff entry: `t` / `key`, no id", ffd)
r = W5.eval(pm + '.putFight(1, 1, 8, "bind", "JUMP", 768)')
check(r[0] is False and r[1] == 'form', "10: a binding on a form's set is refused (form: no key of its own)", tuple(r))
check(W5.eval(pm + '.putFight(1, 1, 8, "command", "DSMAP", 5487)')[0] is False, "10: a form this character does not have is refused")
W5.execute(pm + '.clearCombatForm(768, 8)')
check(W5.eval('HDR("wc-ff-1-8")') is None, "10: the form's entry cleared: no fight text", W5.eval('HDR("wc-ff-1-8")'))
# the bare D-pad while the DS WoW toolbar holds it: a command on its fight layer is cast in fights (the toolbar's own press otherwise)
W5.execute(pm + '.dpadBar(true)')
ok = W5.eval(pm + '.putFight(1, 1, 1, "command", "DSMAP")')
check(ok is True and W5.eval('HDR("wc-ft-1")') == "/click WowCompanionPadMap", "10: toolbar on: Open Map on the bare D-pad Left's fight layer", ok)
r = W5.eval(pm + '.putFight(1, 1, 2, "bind", "JUMP")')
check(r[0] is False and r[1] == 'dpad bar', "10: toolbar on: a binding on the bare D-pad is refused (dpad bar)", tuple(r))
W5.execute('fightOn()')
a, kind, mt = W5.eval('key("PADDLEFT")')
check(kind == "slotfight" and mt == "/click WowCompanionPadMap", "10: toolbar on, in a fight: D-pad Left runs the fight layer's Open Map", (a, kind, mt))
W5.execute('fightOff()')
a, kind, mt = W5.eval('key("PADDLEFT")')
check(kind != "slotfight", "10: toolbar on, out of combat: D-pad Left is the toolbar's again", (a, kind, mt))
W5.execute(pm + '.clearCombat(1, 1, 1); ' + pm + '.dpadBar(false)')
clean(W5, "10")

# ================================================================ 11. Interact i152 (router-heal; the user, 2026-11-12: "I died and then my
# jump input just stopped working"): the router's PRE snippet out of combat - wc-mode nil gives A to the game's own bar button (Jump on
# the gamepad bar), wc-mode "room" sends it to Interact's sink (which nobody holds out of a fight: why Interact never leaves it there)
W6 = boot()
W6.execute('router(8):SetAttribute("wc-room-dest", "WowCompanionInteractPad")')     # what Interact's routerArm sets


def pre_a(L):
    L.execute('doClick(router(8), "LeftButton", true)')
    got = tuple(L.eval('router(8).a["%s"]' % k) for k in ('wc-kind', 'type', 'macrotext', 'wc-at'))
    L.execute('doClick(router(8), "LeftButton", false)')
    return got


got = pre_a(W6)
check(got == ('native', 'macro', '/click GPTop8 LeftButton 1', 'GPTop8'),
      "11: out of combat, wc-mode nil: A's PRE is the game's own bar button (GPTop8, Jump)", got)
W6.execute('router(8):SetAttribute("wc-mode", "room")')
got = pre_a(W6)
check(got[2] == '/click WowCompanionInteractPad PAD1 1' and got[3] == 'WowCompanionInteractPad',
      "11: out of combat, wc-mode \"room\" (no holder): A's PRE goes to Interact's sink - nothing in the game", got)
W6.execute('router(8):SetAttribute("wc-mode", nil)')
got = pre_a(W6)
check(got[3] == 'GPTop8', "11: wc-mode cleared again: A is the game's bar button again", got)
clean(W6, "11")

print('ALL PASS (%d checks)' % passes if fails == 0 else '%d FAILED (of %d)' % (fails, fails + passes))
sys.exit(1 if fails else 0)
