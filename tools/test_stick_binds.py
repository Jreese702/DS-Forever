"""STICK BUTTONS (GitHub #78 "Auto Run on L3"; PadMap m150, PadTabCtl p49), off-device: the REAL add-on files under the stubbed game.

    python tools/test_stick_binds.py

The 1.0.18 player report: "No way to remap auto run to L3 on contrller". The Controller tab's WoW Defaults gets L3 / R3 (positions 9 /
10) for the m34 bindings only (Jump, Interact, Auto Run, Walk / Run), bare and with ZL, stored per character in the bindStore, bound
by the router's SetOverrideBinding on its own header out of combat. Proven here:
  1 Auto Run on bare L3, Jump on ZL + L3, Interact on R3, Walk / Run on ZL + R3: the chords, the owner (our header), the store keys
    ("1:9" ... per name-realm), the document's `sticks`; carried into a fight (no breach), the same after it;
  2 the rules: ZR / ZL + ZR refuse ("layer"); a Move onto a stick refuses; in combat refused (m157: a spell / command / item /
    mount on a stick is allowed now: tools/test_sticks_any.py);
  3 per character: another character has none (a fresh load), the owner's comes back;
  4 Clear: the store entry and the override go; the key is the game's again;
  5 a room holding the pad (Map's L3 pin): no stick override while it holds; back when it lets go;
  6 Party mode on: the stick bindings step aside (bindPlan's rule), back when it is off;
  7 the game takes the stick back: ONE more bind (the fallback, read in both layers), taken again -> "taken": no longer bound, the
    document says st = "taken", the padack says "stick taken", the check stops; a fresh put starts again; a refused first bind -> one
    retry, then taken;
  8 the room (PadTabCtl): the cursor walks onto L3 / R3 on WoW Defaults only (not In combat), A opens the chooser (p52: on Spells,
    all five tabs), Extra's Auto Run binds it; by touch: talentn 38 + button code 1 = L3; p51: the pair sits below the diamonds
    (Down from D-pad Down = L3, from the face bottom = R3);
  9 m155: an empty stick carries the game's own binding (gd / gn / gi: the gamepad context, then the plain layer, overrides left
    out; out of combat only, a fight keeps the last read); one of ours wins; read only;
plus no spied call (no SetBinding, SaveBindings, macro, CVar or bar write). Needs lupa. Exit code 1 on any failure."""
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
-- the GAME TAKING A KEY BACK: TAKE[key] = the action the game puts back on it right after any override change (a binding of its own)
TAKE = {}
local rawSOB = SetOverrideBinding
function SetOverrideBinding(owner, prio, key, action)
    rawSOB(owner, prio, key, action)
    if TAKE[key] then BIND[key] = TAKE[key] end
end
-- the controller's binding context (Enum.BindingContext): GetBindingAction(key, true, ctx) answers like the plain read
Enum = Enum or {}
Enum.BindingContext = Enum.BindingContext or { GamepadModeInGameCore = 3 }
CTXREADS = 0
local rawGBA = GetBindingAction
function GetBindingAction(key, ovr, ctx) if ctx ~= nil then CTXREADS = CTXREADS + 1 end return rawGBA(key, ovr, ctx) end
HOLDER = nil
'''
AFTER = r'''
WowCompanion.spellsStorageFor = function(page, set, pos) return (page - 1) * 48 + (set - 1) * 12 + pos end
local ctl = WowCompanion._spellsBook.ctl
ctl.croom, ctl.ctrlTab, ctl.onTabs = true, true, true
function pp(key) return WowCompanion.padTabCtl.press(key) end
function HDRF() return rawget(_G, "WowCompanionFaceRouter") end
function fightOn() fire("PLAYER_REGEN_DISABLED"); COMBAT = true; driveAll() end
function fightOff() COMBAT = false; driveAll(); fire("PLAYER_REGEN_ENABLED"); runTimers(NOW + 1) end
function STORE() local t = (WowCompanionSpellsDB.binds or {})[WHO .. "-" .. REALM]; return t end
function step(s) NOW = NOW + (s or 1.2); tick(0.1); runTimers(NOW) end
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


def sticks(L):
    L.execute('WowCompanion.padMap.emit(true)')
    m = docs(L, 'padmap')[-1]['m']
    return {(e['set'], e['pos']): e for e in m.get('sticks', [])}


def bind(L, k):
    return L.eval('BIND["%s"]' % k)


def owner_is_header(L, k):
    return L.eval('BOWNER["%s"] == HDRF()' % k) is True


def store(L):
    t = L.eval('STORE()')
    return dict(t) if t is not None else {}


def clean(L, name):
    check(L.eval('#SPY') == 0 and L.eval('#VIOL') == 0 and L.eval('#SNIPERR') == 0 and list(L.eval('ERRS').values()) == [],
          name + ": no spied call (no trace), no lockdown breach, no snippet error",
          (L.eval('SPY[1]'), L.eval('VIOL[1]'), L.eval('SNIPERR[1]'), list(L.eval('ERRS').values())[:2]))


def ctl_doc(L):
    d = docs(L, 'padtabctl')
    return d[-1]['c'] if d else {}


def acks(L, op=None):
    return [a for a in docs(L, 'padack') if op is None or a.get('op') == op]


comp = lupa.LuaRuntime().eval('function(s, n) local f, e = loadstring(s, n); return e end')
for k in ("padmap", "padtabctl"):
    e = comp(SRC[k], k)
    check(e is None, "Lua 5.1 compiles %s (200 locals, 60 upvalues)" % os.path.basename(F[k]), e or '')
check('local PV = "m159"' in SRC["padmap"] and 'local CV = "p53"' in SRC["padtabctl"], "the versions: PadMap m157, PadTabCtl p52 (stick buttons)")
STK = "\n".join(l.split("--", 1)[0] for l in
                SRC["padmap"].split('-- m150 STICK BUTTONS (GitHub #78)\n', 1)[1].split('\nWC.padMap = {', 1)[0].splitlines())
check(not re.search(r'(?<![\w.:])SetBinding\s*\(', STK) and 'SaveBindings' not in STK and 'SetOverrideBinding' in STK,
      "the stick code writes no key binding of the game's (no SetBinding / SaveBindings: overrides only)")

# ================================================================ 1. the four chords
W = boot()
check(list(W.eval('ERRS').values()) == [] and W.eval('#SNIPERR') == 0, "the world loads", list(W.eval('ERRS').values())[:2])
pm = 'WowCompanion.padMap'
ok1 = W.eval(pm + '.putBind(1, 1, 9, "TOGGLEAUTORUN")')
ok2 = W.eval(pm + '.putBind(1, 2, 9, "JUMP")')
ok3 = W.eval(pm + '.putBind(1, 1, 10, "INTERACTTARGET")')
ok4 = W.eval(pm + '.putBind(1, 2, 10, "TOGGLERUN")')
check(ok1 is True and ok2 is True and ok3 is True and ok4 is True, "putBind on L3 / ZL + L3 / R3 / ZL + R3 accepted", (ok1, ok2, ok3, ok4))
check(bind(W, "PADLSTICK") == "TOGGLEAUTORUN" and owner_is_header(W, "PADLSTICK"),
      "Auto Run on bare L3: PADLSTICK -> TOGGLEAUTORUN, our router header's override", bind(W, "PADLSTICK"))
check(bind(W, "SHIFT-PADLSTICK") == "JUMP" and owner_is_header(W, "SHIFT-PADLSTICK"), "Jump on ZL + L3 (SHIFT-PADLSTICK)",
      bind(W, "SHIFT-PADLSTICK"))
check(bind(W, "PADRSTICK") == "INTERACTTARGET" and bind(W, "SHIFT-PADRSTICK") == "TOGGLERUN",
      "Interact on R3, Walk / Run on ZL + R3", (bind(W, "PADRSTICK"), bind(W, "SHIFT-PADRSTICK")))
st = store(W)
check(st.get("1:9") == "TOGGLEAUTORUN" and st.get("2:9") == "JUMP" and st.get("1:10") == "INTERACTTARGET" and st.get("2:10") == "TOGGLERUN",
      "stored for this character (binds[name-realm], \"set:pos\")", st)
sk = sticks(W)
e = sk.get((1, 9), {})
check(e.get('kind') == 'bind' and e.get('key') == 'TOGGLEAUTORUN' and e.get('stick') == 'L3' and 'st' not in e,
      "the document's sticks: bare L3 = Auto Run (kind bind, stick L3, not taken)", e)
check(sk.get((2, 10), {}).get('key') == 'TOGGLERUN' and sk.get((2, 10), {}).get('stick') == 'R3', "... ZL + R3 = Walk / Run", sk.get((2, 10)))
check(W.eval('CTXREADS') > 0, "each stick bind is read back in the gamepad context too (m7's lesson)", W.eval('CTXREADS'))
m = docs(W, 'padmap')[-1]['m']
check(all(len(s_['slots']) == 8 for s_ in m['sets']), "the eight buttons' sets are unchanged (the sticks ride their own list)")
W.execute('fightOn()')
check(bind(W, "PADLSTICK") == "TOGGLEAUTORUN" and bind(W, "SHIFT-PADRSTICK") == "TOGGLERUN" and W.eval('#VIOL') == 0,
      "a fight's start (before the lockdown): the stick bindings are carried", (bind(W, "PADLSTICK"), W.eval('VIOL[1]')))
W.execute('fightOff()')
check(bind(W, "PADLSTICK") == "TOGGLEAUTORUN", "after the fight: L3 is still Auto Run", bind(W, "PADLSTICK"))
clean(W, "1")

# ================================================================ 2. the rules
r = W.eval(pm + '.putBind(1, 3, 9, "JUMP")')
check(r[0] is False and r[1] == "layer" if isinstance(r, tuple) else False, "ZR + L3 refused (layer: the game pages ZR)", r)
r = W.eval(pm + '.putBind(1, 4, 10, "JUMP")')
check(isinstance(r, tuple) and r[1] == "layer", "ZL + ZR + R3 refused (layer)", r)
r = W.eval(pm + '.putBind(1, 1, 9, "STARTATTACK")')
check(isinstance(r, tuple) and r[1] == "unknown" and bind(W, "PADLSTICK") == "TOGGLEAUTORUN", "a command is no stick binding (unknown)", r)
# (m157: a spell / a command / an item / a mount on a stick is no refusal any more: tools/test_sticks_any.py)
r = W.eval(pm + '.move(1, 1, 9, 1, 1, 1)')
check((isinstance(r, tuple) and r[0] is False) or r is False, "Move from a stick refused", r)
check(W.eval(pm + '.bindAllowed(1, 9)') is True and W.eval(pm + '.bindAllowed(2, 10)') is True,
      "bindAllowed: bare and ZL sticks (not the fixed four's rule, not the toolbar's)")
W.execute('COMBAT = true')
r = W.eval(pm + '.putBind(1, 1, 9, "JUMP")')
check(isinstance(r, tuple) and r[1] == "combat" and store(W).get("1:9") == "TOGGLEAUTORUN", "in combat: refused, nothing changed", r)
r = W.eval(pm + '.clear(1, 1, 9)')
check(isinstance(r, tuple) and r[1] == "combat", "Clear in combat refused", r)
W.execute('COMBAT = false')
clean(W, "2")

# ================================================================ 3. per character
key_ = W.eval('WHO .. "-" .. REALM')
db = W.eval('WowCompanionSpellsDB.binds')
binds = {k: dict(v) for k, v in dict(db).items()}
W2 = boot('WHO = "Alt"; WowCompanionSpellsDB = { binds = { ["%s"] = { ["1:9"] = "TOGGLEAUTORUN" } } }' % key_)
check(bind(W2, "PADLSTICK") in (None, "") and sticks(W2).get((1, 9), {}).get('kind') == 'none',
      "another character: L3 is the game's (nothing of Korvash's)", bind(W2, "PADLSTICK"))
W3 = boot('WowCompanionSpellsDB = { binds = { ["%s"] = { ["1:9"] = "TOGGLEAUTORUN" } } }' % key_)
check(bind(W3, "PADLSTICK") == "TOGGLEAUTORUN", "a fresh load of the owner: L3 is Auto Run again (the saved store)", bind(W3, "PADLSTICK"))
clean(W3, "3")

# ================================================================ 4. Clear
ok = W.eval(pm + '.clear(1, 1, 9)')
check(ok is True and "1:9" not in store(W) and bind(W, "PADLSTICK") in (None, ""), "Clear: the entry and the override are gone",
      (ok, store(W), bind(W, "PADLSTICK")))
check(bind(W, "SHIFT-PADLSTICK") == "JUMP", "... the other stick chords stay", bind(W, "SHIFT-PADLSTICK"))
r = W.eval(pm + '.clear(1, 1, 9)')
check(isinstance(r, tuple) and r[1] == "empty", "Clear on an empty stick: nothing to do", r)
clean(W, "4")

# ================================================================ 5. a room holds the pad (Map's L3 pin)
W.eval(pm + '.putBind(1, 1, 9, "TOGGLEAUTORUN")')
W.execute('HOLDER = "map"; step()')
check(bind(W, "PADLSTICK") in (None, ""), "the Map room holds the pad: no stick override (its L3 pin wins)", bind(W, "PADLSTICK"))
W.execute('HOLDER = nil; step()')
check(bind(W, "PADLSTICK") == "TOGGLEAUTORUN", "the room lets go: L3 is Auto Run again", bind(W, "PADLSTICK"))
# review: a room let go is no re-take by the game (the stick was stepped aside, not bound): three visits in 30 s stay bound
for _ in range(3):
    W.execute('HOLDER = "map"; step()')
    W.execute('HOLDER = nil; step()')
check(bind(W, "PADLSTICK") == "TOGGLEAUTORUN" and sticks(W)[(1, 9)].get('st') is None,
      "three room visits within the window: L3 still Auto Run, never \"taken\"", (bind(W, "PADLSTICK"), sticks(W)[(1, 9)].get('st')))
check(not any(a.get('why') == 'stick taken' for a in acks(W)), "... and no \"stick taken\" padack")
clean(W, "5")

# ================================================================ 6. Party mode
P = boot('WowCompanion = WowCompanion or {}; WowCompanion.partyOk = true')
P.execute('UNITS.party1 = { name = "Ana", cls = "PRIEST", alive = true }')
P.eval(pm + '.putBind(1, 1, 9, "TOGGLEAUTORUN")')
check(bind(P, "PADLSTICK") == "TOGGLEAUTORUN", "a Party-mode world: L3 is Auto Run with Party mode off", bind(P, "PADLSTICK"))
P.execute('clickName("WowCompanionPartyOn", true); clickName("WowCompanionPartyOn", false); step()')
on = P.eval('WowCompanion.padMap.party and WowCompanion.padMap.party.on and WowCompanion.padMap.party.on()')
if on:
    check(bind(P, "PADLSTICK") in (None, ""), "Party mode on: the stick binding steps aside (bindPlan's rule)", bind(P, "PADLSTICK"))
    P.execute('clickName("WowCompanionPartyOff", true); clickName("WowCompanionPartyOff", false); step()')
    check(bind(P, "PADLSTICK") == "TOGGLEAUTORUN", "Party mode off: L3 is Auto Run again", bind(P, "PADLSTICK"))
else:
    P.execute('rawget(_G, "WowCompanionSpellsDB").partyOn = nil')
    check(True, "(Party mode's toggle needs a fight here; its step-aside is the same guard as every binding's: see test_fight_layer 8)")
clean(P, "6")

# ================================================================ 7. the game takes it back
G = boot()
G.eval(pm + '.putBind(1, 1, 9, "TOGGLEAUTORUN")')
check(bind(G, "PADLSTICK") == "TOGGLEAUTORUN" and sticks(G)[(1, 9)].get('st') is None, "bound and read back fine")
G.execute('BIND["PADLSTICK"] = "TOGGLEWORLDMAP"; step(0.3)')            # the game re-takes the key once
G.execute('step()')
check(bind(G, "PADLSTICK") == "TOGGLEAUTORUN" and sticks(G)[(1, 9)].get('st') is None,
      "a first re-take: the fallback binds it ONCE more (L3 is Auto Run, not taken)", bind(G, "PADLSTICK"))
G.execute('BIND["PADLSTICK"] = "TOGGLEWORLDMAP"; step(0.3); step(); step()')  # and again
e = sticks(G)[(1, 9)]
check(e.get('st') == 'taken' and bind(G, "PADLSTICK") != "TOGGLEAUTORUN" and not owner_is_header(G, "PADLSTICK"),
      "taken again within the window: \"taken\", no longer bound (L3 is the game's again)", (e, bind(G, "PADLSTICK")))
check(any(a.get('why') == 'stick taken' and a.get('op') == 'bind' for a in acks(G)), "the padack says \"stick taken\" (the room shows it)")
G.execute('step(); step(); step()')
check(bind(G, "PADLSTICK") != "TOGGLEAUTORUN" and not owner_is_header(G, "PADLSTICK"), "the check stops: nothing rebinds a taken stick",
      bind(G, "PADLSTICK"))
check(store(G).get("1:9") == "TOGGLEAUTORUN", "the player's choice stays stored (shown as taken)", store(G))
G.eval(pm + '.putBind(1, 1, 9, "TOGGLEAUTORUN")')
check(bind(G, "PADLSTICK") == "TOGGLEAUTORUN" and sticks(G)[(1, 9)].get('st') is None, "a fresh put starts it again", bind(G, "PADLSTICK"))
clean(G, "7a")
# a first bind the game refuses at once (a binding of its own put straight back): one retry, then taken
H = boot('TAKE = { PADRSTICK = "TOGGLEWORLDMAP" }')
H.eval(pm + '.putBind(1, 1, 10, "INTERACTTARGET")')
check(sticks(H)[(1, 10)].get('st') is None, "R3 refused at its first bind: one loss, not taken yet")
H.execute('step(); step()')
e = sticks(H)[(1, 10)]
check(e.get('st') == 'taken' and bind(H, "PADRSTICK") == "TOGGLEWORLDMAP", "the retry refused too: taken, the key left to the game",
      (e, bind(H, "PADRSTICK")))
check(sticks(H)[(1, 9)].get('kind') == 'none', "the other stick is untouched")
clean(H, "7b")

# ================================================================ 8. the room (PadTabCtl)
R = boot()
R.execute('WowCompanion.padTabCtl.enter(); WowCompanion.padTabCtl.touchTab(1)')
R.execute('WowCompanion.padTabCtl.touchPos(4)')                         # D-pad Down
R.execute('pp("PADDDOWN")')
c = ctl_doc(R)
check(c.get('pos') == 9 and c.get('view') == 'normal', "WoW Defaults: Down from D-pad Down lands on L3 (position 9; p51: the pair below)", c.get('pos'))
R.execute('pp("PADDRIGHT")')
check(ctl_doc(R).get('pos') == 10, "... Right again: R3 (10)", ctl_doc(R).get('pos'))
R.execute('pp("PADDUP")')
check(ctl_doc(R).get('pos') in (5, 6), "Up from R3: the face diamond's left button", ctl_doc(R).get('pos'))
R.execute('WowCompanion.padTabCtl.touchPos(4); pp("PADDDOWN"); pp("PADDRIGHT"); pp("PADDLEFT")')
check(ctl_doc(R).get('pos') == 9, "p51: Left from R3: L3", ctl_doc(R).get('pos'))
R.execute('pp("PADDUP")')
check(ctl_doc(R).get('pos') == 3, "p51: Up from L3: the D-pad's Right (straight above it)", ctl_doc(R).get('pos'))
R.execute('WowCompanion.padTabCtl.touchPos(7); pp("PADDDOWN")')
check(ctl_doc(R).get('pos') == 10, "p51: Down from the face diamond's bottom (B, Nintendo layout): R3", ctl_doc(R).get('pos'))
R.execute('pp("PADDRIGHT")')
check(ctl_doc(R).get('pos') == 7, "p51: Right from R3: back to the face diamond's bottom", ctl_doc(R).get('pos'))
R.execute('WowCompanion.padTabCtl.touchPos(4); pp("PADDDOWN"); pp("PAD1")')
c = ctl_doc(R)
check(c.get('chooser') and c['chooser'].get('list') == 'spells', "A on L3: the chooser opens on Spells (m157 / p52: all five tabs)",
      c.get('chooser'))
R.execute('pp("PADDRIGHT"); pp("PADDRIGHT"); pp("PADDRIGHT")')
check(ctl_doc(R)['chooser'].get('list') == 'extra', "Right x3: the Extra tab (its game bindings among the commands)", ctl_doc(R)['chooser'])
# walk to Auto Run (Extra's rows: the commands, then Jump, Interact, Auto Run, Walk / Run) and choose it
for _ in range(60):
    if ctl_doc(R)['chooser'].get('id') == 'TOGGLEAUTORUN':
        break
    R.execute('pp("PADDDOWN")')
R.execute('pp("PAD1")')
check(bind(R, "PADLSTICK") == "TOGGLEAUTORUN" and store(R).get("1:9") == "TOGGLEAUTORUN",
      "choosing Auto Run there binds bare L3 (the controller's route)", (bind(R, "PADLSTICK"), store(R)))
R.execute('pp("PAD3")')
check(ctl_doc(R).get('note') == 'bind-move' and not ctl_doc(R).get('carry'), "X (Move) on a stick: refused (it rides the key)",
      ctl_doc(R).get('note'))
R.execute('WowCompanion.padTabCtl.touchTab(3)')
c = ctl_doc(R)
check(c.get('view') == 'combat' and c.get('pos') == 4, "In combat has no sticks: the cursor goes back to a button (L3 -> D-pad Down)", c.get('pos'))
R.execute('WowCompanion.padTabCtl.touchPos(4); pp("PADDRIGHT"); pp("PADDDOWN")')
check(ctl_doc(R).get('pos') not in (9, 10), "In combat: Right / Down never reach L3 / R3", ctl_doc(R).get('pos'))
# by touch: talentn 38 then button code 2 = R3; a spell / a command there refused; the Extra row's binding binds it
R.execute('WowCompanion.padTabCtl.touchTab(1); WowCompanion.padTabCtl.commandsFrom12(); WowCompanion.padTabCtl.touchPos(2)')
check(ctl_doc(R).get('pos') == 10, "by touch: the one-shot talentn 38 pick + button code 2 = R3", ctl_doc(R).get('pos'))
R.execute('WowCompanion.padTabCtl.touchPos(2)')
check(ctl_doc(R).get('pos') == 2, "... without the pick, code 2 is D-pad Up as before", ctl_doc(R).get('pos'))
R.execute('WowCompanion.padTabCtl.commandsFrom12(); WowCompanion.padTabCtl.touchPos(2)')
# (m157 / p52: a spell / a command by touch on a stick goes on it: tools/test_sticks_any.py section 8)
idx = R.eval('(function() for i, x in ipairs(WowCompanion.padMap.extraList()) do if x.kind == "bind" and x.key == "TOGGLERUN" then return i end end end)()')
R.execute('WowCompanion.padTabCtl.touchExtraPage(0)')
okx = R.eval('WowCompanion.padTabCtl.touchExtra(%d)' % (idx - 1))
check(bind(R, "PADRSTICK") == "TOGGLERUN" and store(R).get("1:10") == "TOGGLERUN", "the Extra row's Walk / Run by touch binds bare R3",
      (okx, bind(R, "PADRSTICK")))
R.execute('WowCompanion.padTabCtl.touchClear()')
check(bind(R, "PADRSTICK") in (None, "") and "1:10" not in store(R), "Clear by touch on R3", bind(R, "PADRSTICK"))
clean(R, "8")

# ================================================================ 9. m155 the game's own on an empty stick (the user, 2026-11-13: "L3 and
# R3 should show the wow default options on them by default"): read with GetBindingAction(chord, false, ctx), then the plain layer;
# overrides left out (false), so never ours; out of combat only; read only
GD_PRE = r'''
BINDING_NAME_TOGGLEWORLDMAP = "Toggle World Map"
GAMECTX = { PADLSTICK = "TOGGLERUN", ["SHIFT-PADRSTICK"] = "TOGGLEWORLDMAP" }
GAMEPLAIN = { PADRSTICK = "SPELL Frost Nova", PADLSTICK = "JUMP" }
GDREADS, GDCOMBAT = 0, 0
local gba = GetBindingAction
function GetBindingAction(key, ovr, ctx)
    if ovr == false and type(key) == "string" and key:find("STICK") then
        GDREADS = GDREADS + 1
        if COMBAT then GDCOMBAT = GDCOMBAT + 1 end
        if ctx ~= nil then return GAMECTX[key] or "" end
        return GAMEPLAIN[key] or ""
    end
    return gba(key, ovr, ctx)
end
'''
D = boot(GD_PRE)
sk = sticks(D)
l3 = sk.get((1, 9), {})
check(l3.get('kind') == 'none' and l3.get('gd') == 'TOGGLERUN' and l3.get('gn') == 'Walk / Run' and l3.get('gi') == 132539,
      "an empty bare L3 carries the game's own (the gamepad context's TOGGLERUN, ahead of the plain layer's Jump): gd / gn / gi", l3)
z3 = sk.get((2, 10), {})
check(z3.get('gd') == 'TOGGLEWORLDMAP' and z3.get('gn') == 'Toggle World Map', "ZL + R3: the game's word for its action (BINDING_NAME_)", z3)
r3 = sk.get((1, 10), {})
check(r3.get('gd') == 'SPELL Frost Nova' and r3.get('gn') == 'Frost Nova' and r3.get('gi') == 0,
      "bare R3, nothing in the context: the plain layer's (a spell binding: its name, no icon)", r3)
check('gd' not in sk.get((2, 9), {}) and sk.get((2, 9), {}).get('kind') == 'none', "ZL + L3, nothing anywhere: no gd (empty as before)",
      sk.get((2, 9)))
check(D.eval('GDREADS') > 0, "read with GetBindingAction(chord, false, ...) (overrides left out)", D.eval('GDREADS'))
D.eval(pm + '.putBind(1, 1, 9, "TOGGLEAUTORUN")')
l3 = sticks(D).get((1, 9), {})
check(l3.get('kind') == 'bind' and l3.get('key') == 'TOGGLEAUTORUN' and 'gd' not in l3,
      "a DS Forever bind wins: L3 shows Auto Run, no game default beside it", l3)
D.eval(pm + '.clear(1, 1, 9)')
check(sticks(D).get((1, 9), {}).get('gd') == 'TOGGLERUN', "cleared: the game's own shows again", sticks(D).get((1, 9)))
D.execute('fightOn(); GDCOMBAT = 0; GAMECTX.PADLSTICK = "TOGGLEAUTORUN"')
l3 = sticks(D).get((1, 9), {})
check(D.eval('GDCOMBAT') == 0 and l3.get('gd') == 'TOGGLERUN', "in a fight: nothing read, the last read kept", (D.eval('GDCOMBAT'), l3))
D.execute('fightOff()')
check(sticks(D).get((1, 9), {}).get('gd') == 'TOGGLEAUTORUN', "after the fight: read again (the game's change shows)", sticks(D).get((1, 9)))
m = docs(D, 'padmap')[-1]['m']
check(all('gd' not in sl for s_ in m['sets'] for sl in s_['slots']), "the eight buttons never carry gd (sticks only)")
clean(D, "9")
# STICK_DUMP=<dir>: the room's own documents for the render (StickTilesShotsTest): WoW Defaults, the cursor on L3; then Auto Run on L3
dump = os.environ.get("STICK_DUMP")
if dump:
    os.makedirs(dump, exist_ok=True)
    D.execute('GAMECTX.PADLSTICK = "TOGGLERUN"; WowCompanion.padTabCtl.enter(); WowCompanion.padTabCtl.touchTab(1); WowCompanion.padTabCtl.touchPos(4); pp("PADDDOWN")')

    def put(name):
        D.execute('WowCompanion.padMap.emit(true)')
        with open(os.path.join(dump, name + "_padmap.json"), "w", encoding="utf-8") as f:
            json.dump(docs(D, 'padmap')[-1], f)
        with open(os.path.join(dump, name + "_ctl.json"), "w", encoding="utf-8") as f:
            json.dump(docs(D, 'padtabctl')[-1], f)
    put("sticks_game")
    D.eval(pm + '.putBind(1, 1, 9, "TOGGLEAUTORUN")')
    put("sticks_bound")

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
