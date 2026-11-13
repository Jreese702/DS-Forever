"""Party mode (PARTY_BUILD.md, the ADD-ON half), off-device: the REAL add-on files under a stubbed game, Lua 5.1 as the game's.

    python tools/test_party.py

Groups (PARTY_BUILD.md 5.4): 1 the router's PRE with Party mode; 2 PARTYPAD (NAV, casts, Y, B, the menu sub-state, key-up);
3 PARTYFLIP; 4 the bindings (a spying binding table); 5 the combat lockdown; 6 the per-character store and the class defaults;
7 the documents; 8 the health estimate; 9 Smart Y's party picks and PB ops; 10 TapPad pad11; 11 Quests' player menu for a unit;
plus the Party tab (PadTabCtl p33 / Book b47) and the no-trace scan. Secure snippets run as plain Lua in a RESTRICTED environment
(only what the game's restricted environment offers that the snippets need). Needs lupa. Exit code 1 on any failure."""
import json
import os
import re
import sys

from lupa import lua51 as lupa                    # the game's Lua is 5.1

# the stubbed game (GAME), boot / docs / nsent and the file map live in tools/wowstub.py since 2026-11-11 (shared with test_combat_pad.py)
from wowstub import ROOT, ADDONS, F, SRC, GAME, boot, docs, nsent

fails = 0
passes = 0


# m133: PRE with the chord flip's body (OV.fbSnip("nil")) spliced in at its mark: the router's whole snippet, as the add-on builds it
def full_pre(src):
    pre = re.search(r'local PRE = \[=\[(.*?)\]=\]', src, re.S).group(1)
    body = re.search(r'function OV\.fbSnip\(fbf\)\n    return .*?\[=\[(.*?)\]=\]', src, re.S).group(1)
    mark = "-- m133 FBSNIP: the chord flip (OV.fbSnip) is put here, below"
    assert pre.count(mark) == 1
    return pre.replace(mark, "\n    do\n    local fbf = nil\n" + body)


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


# ================================================================ STATIC: every touched file compiles as Lua 5.1, under its limits
SL = lupa.LuaRuntime()
comp = SL.eval('function(s, n) local f, e = loadstring(s, n); return e end')
for k in ("tappad", "quests", "book", "padmap", "padtabctl"):
    e = comp(SRC[k], k)
    check(e is None, "Lua 5.1 compiles %s (200 locals, 60 upvalues)" % os.path.basename(F[k]), e or '')

# ================================================================ 10. TapPad pad11
check('local PADV = "pad19"' in SRC["tappad"], "TapPad says pad19 (pad11's party row + the resting cursor hidden under the software cursor)")
check(re.search(r'local ROWS_MAX = 56\b', SRC["tappad"]) is not None, "TapPad ROWS_MAX = 56")


def pad_at(popup_top):
    L = boot(["safety", "tappad"], pre='SCREEN.uh = 1080 / (%r / 135)' % popup_top)
    L.execute('''
        for i = 0, 55 do
            if i % 2 == 0 then WowCompanion.pad.op("r" .. i, nil, function() end)
            else WowCompanion.pad.secureRow("s" .. i, 3, function(b, c) b:SetAttribute("type", nil) end, nil, { onPress = true }) end
        end
        X57 = WowCompanion.pad.op("r56", nil, function() end)
        WowCompanion.pad.layout()
    ''')
    d = L.eval('WowCompanion.pad.describe()')
    rows = sorted(int(v) for v in d['rows'].values())
    return L, d, rows


L10, d, rows = pad_at(126.6)
check(abs(float(d['popupTop']) - 126.6) < 0.06 and rows == list(range(56)), "pad11 at popupTop 126.6: rows 0..55 placed and described",
      (d['popupTop'], rows[-3:], len(rows)))
check(L10.eval('rawget(_G, "X57")') is None, "a 57th row does not register (ROWS_MAX 56)")
check(d['v'] == 'pad19', "describe() says pad19", d['v'])
L10, d, rows = pad_at(121.5)
check(abs(float(d['popupTop']) - 121.5) < 0.06 and rows == list(range(54)), "pad11 at popupTop 121.5 (smallest scale): rows 0..53 only",
      (d['popupTop'], rows[-3:], len(rows)))
hidden = L10.eval('(function() local r = WowCompanion.pad.rows["r54"]; local s = WowCompanion.pad.rows["s55"]; '
                  'return (r.cells[0]._shown == false) and (s.cells[0]._shown == false) end)()')
check(hidden is True, "rows 54-55 are hidden at the smallest scale (never inside a dialog)", hidden)

# ================================================================ 1. PRE + Party mode (the router's real snippet, restricted)
PRE = full_pre(SRC["padmap"])
# m94 (test9, 2026-11-07: every A / X / Y / B / D-pad press dead): the game's secure checker refuses the word "function" ANYWHERE in a
# snippet, comments included ("The function keyword is not permitted"); no secure snippet of any add-on may hold it
_snip_bad = []
for _k, _src in SRC.items():
    for _m in re.finditer(r'\[=*\[(.*?)\]=*\]', _src, re.S):
        if re.search(r'\bfunction\b', _m.group(1)) and ('SetAttribute' in _m.group(1) or 'self:' in _m.group(1)):
            _snip_bad.append((_k, _src[:_m.start()].count('\n') + 1))
check(not _snip_bad, "no secure snippet holds the word 'function' (the game refuses the whole snippet, comments too)", _snip_bad)
G1 = boot([])
prefn = G1.eval('function(body) return compileSnippet(body, "self, button, down") end')(PRE)
G1.globals().PREFN = prefn
G1.execute('''
function mk(attrs)
    local f = setmetatable({ a = attrs, refs = {}, hooks = {}, scripts = {}, wraps = {}, clicks = {}, _name = "R" }, Frame)
    f.refs["wc-hdr"] = setmetatable({ a = {}, refs = {} }, Frame)
    f.refs["wc-bar-top-1"] = setmetatable({ a = {}, refs = {}, _shown = true }, Frame)
    f.refs["wc-btn-top-1"] = setmetatable({ a = {}, refs = {}, _id = 5 }, Frame)
    for _, p in ipairs({ 1, 2, 3, 4, 5, 6, 8 }) do f.refs["wc-face-" .. p] = setmetatable({ a = {}, refs = {} }, Frame) end
    return f
end
-- one router button per role, with EVERYTHING that could take a press armed: a fight spell in the fight state, fishing, Smart Y, the
-- quest item, the D-pad toolbar, a slot macro / fight spell, start attack, B's give-back
function routerBtn(pos, key, party, roomMode)
    local role = (pos == 7 and "back") or (pos <= 4 and "dpad") or nil
    local f = mk({ ["wc-n-top"] = 1, ["wc-dest-top-1"] = "GPTop" .. pos, ["wc-pos"] = pos, ["wc-key"] = key, ["wc-role"] = role,
                   ["wc-state"] = "fight", ["wc-fight-type"] = "spell", ["wc-fight-spell"] = "Lightning Bolt", ["wc-fish"] = (pos == 5) and "Fishing" or nil,
                   ["wc-smarty"] = (pos == 6) and "item:117" or nil, ["wc-qitem"] = (pos == 6) and "item:1" or nil, ["wc-qitem-u"] = "target", ["wc-roombar"] = "WowCompanionPadMap",
                   ["wc-interact"] = (pos == 5) or nil, ["wc-party"] = party, ["wc-party-dest"] = "WowCompanionPartyPad",
                   ["wc-mode"] = roomMode and "room" or nil, ["wc-room-dest"] = "WowCompanionInteractPad" })
    f.refs["wc-hdr"].a["wc-mt-5"] = "/cast Slot Macro"; f.refs["wc-hdr"].a["wc-ft-5"] = "/cast Slot Fight"; f.refs["wc-hdr"].a["wc-sa-5"] = "/startattack\\n"
    return f
end
function pressPre(f, down)
    TRUE_TARGET = true
    runSecure(PREFN, f, "LeftButton", down)
    return f.a["wc-kind"], f.a["type"], f.a["macrotext"], f.a["spell"], f.a["item"], f.a["unit"]
end
UNITS.target = { alive = true, foe = true }
''')
ROUTER8 = [(8, "PAD1"), (7, "PAD2"), (5, "PAD3"), (6, "PAD4"), (1, "PADDLEFT"), (2, "PADDUP"), (3, "PADDRIGHT"), (4, "PADDDOWN")]
for combat in (False, True):
    G1.execute('COMBAT = %s' % ('true' if combat else 'false'))
    for pos, key in ROUTER8:
        f = G1.eval('routerBtn')(pos, key, True, False)
        k = G1.eval('pressPre')(f, True)
        given = any(G1.eval('function(f) for _, p in ipairs({1,2,3,4,5,6,8}) do if f.refs["wc-face-" .. p].a["wc-given"] then return true end end return false end')(f) for _ in [0])
        check(k[0] == "native" and k[1] == "macro" and k[2] == "/click WowCompanionPartyPad %s 1" % key and k[3] is None and k[4] is None
              and k[5] is None and not given,
              "PRE, Party mode, %s%s: the key-down is /click WowCompanionPartyPad %s 1, nothing else" % (key, " in combat" if combat else "", key),
              (k[0], k[2], given))
        k = G1.eval('pressPre')(f, False)
        check(k[2] == "/click WowCompanionPartyPad %s 0" % key, "PRE, Party mode, %s: the key-up is ... %s 0" % (key, key), k[2])
G1.execute('COMBAT = true')
for pos, key in ROUTER8:
    f = G1.eval('routerBtn')(pos, key, True, True)
    k = G1.eval('pressPre')(f, True)
    check(k[2] == "/click WowCompanionInteractPad %s 1" % key, "PRE: wc-mode \"room\" beats wc-party (%s goes to the room)" % key, k[2])
# unset = today (the fight spell / the toolbar / B's give-back), and test_smarty.py's own cases below
f = G1.eval('routerBtn')(5, "PAD3", None, False)
f.a["wc-qitem"] = None
k = G1.eval('pressPre')(f, True)
check(k[0] == "fight" and k[3] == "Lightning Bolt", "PRE without wc-party: X in the fight state is its fight spell, as today", k[:4])
f = G1.eval('routerBtn')(7, "PAD2", None, False)
k = G1.eval('pressPre')(f, True)
given = G1.eval('function(f) return f.refs["wc-face-5"].a["wc-given"] end')(f)
check(given is True, "PRE without wc-party: B in a fight gives A / X / Y back, as today", given)
# m99 (the user: "pressing B in combat ... should stop the auto attack ... Keeping that enemy targeted, pressing B again would drop the target")
check(k[0] == "bstop" and k[1] == "macro" and k[2] == "/stopattack", "m99: the first B in a fight stops the attack (the target kept)", k[:3])
k = G1.eval('pressPre')(f, True)
check(k[0] == "bstop" and k[2] == "/stopattack\n/cleartarget", "m99: B again (already given back) clears the target", k[:3])
# m101 THE TARGET RADIAL (the user, 2026-11-07): a bare Y out of a fight with a target and nothing else on Y -> "tradial", no game action
G1.execute('COMBAT = false')
f = G1.eval('routerBtn')(6, "PAD4", None, False)
f.a["wc-qitem"] = None; f.a["wc-smarty"] = None; f.a["wc-tradial"] = True; f.a["wc-state"] = "idle"
k = G1.eval('pressPre')(f, True)
check(k[0] == "tradial" and k[1] is None, "m101: Y out of a fight with a target: tradial, no game action", k[:3])
f.a["wc-tradial"] = None
k = G1.eval('pressPre')(f, True)
check(k[0] != "tradial", "m101: without wc-tradial (the switch off): Y as before", k[:3])
f.a["wc-tradial"] = True; f.a["wc-qitem"] = "item:1"
k = G1.eval('pressPre')(f, True)
check(k[0] == "qitem", "m101: a quest item for the target still wins over the radial", k[:3])
f.a["wc-qitem"] = None
G1.execute('UNITS.target = nil')
k = G1.eval('pressPre')(f, True)
check(k[0] != "tradial", "m101: no target: Y as before (Forever's Inspect / Smart Y)", k[:3])
G1.execute('UNITS.target = { alive = true, foe = true }')
# m102 (the user: "go away when I /startattack, same rules as the button grow animation"): the controller grown = no radial
f.a["wc-pre"] = True
k = G1.eval('pressPre')(f, True)
check(k[0] == "fight", "m102 / m123: an attack started (wc-pre) with a fight spell on Y: the fight spell", k[:3])
f.a["wc-pre"] = None
G1.execute('COMBAT = true'); f.a["wc-state"] = "fight"
k = G1.eval('pressPre')(f, True)
check(k[0] == "fight", "m123: in a fight (grown) with a fight spell on Y: the fight spell, not the radial", k[:3])
# m123 (the user, 2026-11-10: "pressing y in combat is opening the context menu on the top screen. this should open on the bottom screen")
ft = f.a["wc-fight-type"]; f.a["wc-fight-type"] = None
k = G1.eval('pressPre')(f, True)
check(k[0] == "tradial" and k[1] is None, "m123: in a fight (grown), NO fight spell on Y: the radial, no game action (not the game's menu)", k[:3])
f.a["wc-fight-type"] = ft
f.a["wc-given"] = True
k = G1.eval('pressPre')(f, True)
check(k[0] == "tradial", "m102: in a fight after B (given back): the radial again", k[:3])
f.a["wc-given"] = None; f.a["wc-state"] = "idle"
G1.execute('COMBAT = false')
G1.execute('COMBAT = false')
f = G1.eval('routerBtn')(2, "PADDUP", None, False)
f.a["wc-state"] = "idle"; f.a["wc-qitem"] = None
k = G1.eval('pressPre')(f, True)
check(k[0] == "roombar", "PRE without wc-party: the bare D-pad is the DS toolbar, as today", k[0])
f = G1.eval('routerBtn')(2, "PADDUP", True, False)
f.a["wc-party-dest"] = None; f.a["wc-state"] = "idle"; f.a["wc-qitem"] = None
k = G1.eval('pressPre')(f, True)
check(k[0] == "roombar", "PRE: wc-party with no wc-party-dest changes nothing", k[0])
check(G1.eval('#SNIPERR') == 0, "PRE ran in the restricted environment without an error", G1.eval('SNIPERR[1]'))
import subprocess
r = subprocess.run([sys.executable, os.path.join(ROOT, 'tools', 'test_smarty.py')], capture_output=True, text=True)
check(r.returncode == 0 and r.stdout.strip().endswith('ALL PASS'), "tools/test_smarty.py passes UNCHANGED", r.stdout.strip()[-60:])
# the contract's base b187c3c1 (+ m78 Manners), then m101 (the user's ruling 2026-11-07: a soft-interact target no longer blocks Smart Y):
# the file is pinned by its content now; change it only on a ruling, and re-pin here. Re-pinned for m127 (the user's rulings
# 2026-11-11: no tracking on Y, Manners its own row for other players only, one ordered list with Food / Water movable) and for m128
# (the same order rule: the model's eating / drinking rate and the why line from the step Y uses), and for m132 (the user, 2026-11-11:
# "My current polestar objective does not give any direction on where to go": the picked Polestar step's starting item on Y, "bag")
import hashlib
smarty_sha = hashlib.sha256(open(os.path.join(ROOT, 'tools', 'test_smarty.py'), 'rb').read()).hexdigest()
check(smarty_sha == "0d1ec55e6e24f915391a1d9583351be49acbb8124fe4e724581eaf9268423758", "tools/test_smarty.py is byte for byte the pinned contract (b187c3c1 + m78 Manners + m101 soft-interact + m127 Smart Y v2 + m128 rate per kind + m132 Polestar starter, nothing targeted)", smarty_sha[:12])

# ================================================================ THE WORLD: the real Spells files (and TapPad) logged in
ROUTER_NAME = {8: "WowCompanionCombatFace8", 7: "WowCompanionFaceB", 5: "WowCompanionCombatFace5", 6: "WowCompanionCombatFace6",
               1: "WowCompanionFaceD1", 2: "WowCompanionFaceD2", 3: "WowCompanionFaceD3", 4: "WowCompanionFaceD4"}
KEY_POS = {"PAD1": 8, "PAD2": 7, "PAD3": 5, "PAD4": 6, "PADDLEFT": 1, "PADDUP": 2, "PADDRIGHT": 3, "PADDDOWN": 4}
WORLD_HELPERS = r'''
function party(spec)            -- spec = { {name, cls, alive, role, pt, off, foe}, ... } for party1..
    for k in pairs(UNITS) do if k:match("^party%d$") then UNITS[k] = nil end end
    for i, m in ipairs(spec or {}) do
        UNITS["party" .. i] = { name = m[1], cls = m[2] or "WARRIOR", alive = m[3] ~= false, role = m[4] or "NONE", pt = m[5] or "RAGE",
                                off = m[6], foe = m[7], guid = "Player-1-" .. m[1] }
    end
end
function router(pos) return rawget(_G, ({ [8] = "WowCompanionCombatFace8", [7] = "WowCompanionFaceB", [5] = "WowCompanionCombatFace5",
    [6] = "WowCompanionCombatFace6", [1] = "WowCompanionFaceD1", [2] = "WowCompanionFaceD2", [3] = "WowCompanionFaceD3",
    [4] = "WowCompanionFaceD4" })[pos]) end
-- a hardware press of the router button for `pos` (what its override binding delivers), both edges
function hit(pos) local b = router(pos); doClick(b, "LeftButton", true); doClick(b, "LeftButton", false) end
function trig(lt, rt) PADSTATE = {}; if lt then PADSTATE[LT_I] = true end; if rt then PADSTATE[RT_I] = true end end
function PADA(k) return rawget(_G, "WowCompanionPartyPad").a[k] end
function lastAction() return ACTIONS[#ACTIONS] end
function clickName(n, down) doClick(rawget(_G, n), "LeftButton", down) end
-- the book (Book.lua reads the game's own spellbook, stubbed empty here): the character's spells are BOOK's
WowCompanion.spellsBindableList = function()
    local out = {}
    for i, id in ipairs(BOOK) do out[#out + 1] = { id = id, slot = i } end
    return out
end
WowCompanion.spellsBookSlotOf = function(id) for i, x in ipairs(BOOK) do if x == id then return i end end end
'''


def world(pre='', files=("safety", "tappad", "book", "padmap", "padtabctl"), ok=True):
    # m100: Party mode is an experimental switch (Hub's "uisw" 16 / 17 -> WowCompanion.partyOk); the worlds here have it ON unless ok=False
    L = boot(list(files), pre=('WowCompanion.partyOk = true; ' if ok else '') + pre)
    L.execute(WORLD_HELPERS)
    L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
    return L


def nact(L):
    return int(L.eval('#ACTIONS'))


def acts(L, start):
    out = []
    for i in range(start + 1, nact(L) + 1):
        a = L.eval('ACTIONS[%d]' % i)
        out.append((a.kind, a.spell, a.unit))
    return out


W = world()
check(list(W.eval('ERRS').values()) == [] and W.eval('#SNIPERR') == 0, "the world loads (Safety, TapPad, Book, PadMap, PadTabCtl) with no error",
      list(W.eval('ERRS').values())[:2])
check(all(W.eval('rawget(_G, "%s") ~= nil' % n) for n in ("WowCompanionPartyPad", "WowCompanionPartyToggle", "WowCompanionPartyOn",
                                                             "WowCompanionPartyOff", "WowCompanionPartyMenuDone")),
      "the five secure frames exist by their contract names")
padf = W.eval('rawget(_G, "WowCompanionPartyPad")')
refs = dict(padf.refs)
check(all(refs.get('wc-r-%d' % (i + 1)) is not None and refs['wc-r-%d' % (i + 1)]._name == ROUTER_NAME[p]
          for i, p in enumerate([8, 7, 5, 6, 1, 2, 3, 4])) and padf.a['wc-rn'] == 8 and refs.get('wc-pad') is not None,
      "the pad's refs: wc-r-1..8 in F.ROUTER's order (A B X Y, D-pad L U R D), wc-rn 8, wc-pad")
for n in ("WowCompanionPartyToggle", "WowCompanionPartyOn", "WowCompanionPartyOff", "WowCompanionPartyMenuDone"):
    fr = W.eval('rawget(_G, "%s")' % n)
    check(fr.refs['wc-pad'] is not None and fr.refs['wc-r-8'] is not None and fr.a['wc-rn'] == 8, n + ": wc-pad + wc-r-1..8 refs")
check(all(W.eval('router(%d)' % p).a['wc-party-dest'] == "WowCompanionPartyPad" for p in range(1, 9)),
      "every router button carries wc-party-dest = WowCompanionPartyPad")

# ================================================================ 3. PARTYFLIP
def flip(L, name):
    L.execute('clickName("%s", true); clickName("%s", false); advance(0.3)' % (name, name))


def party_on_all(L):
    return [L.eval('router(%d)' % p).a['wc-party'] for p in range(1, 9)] + [L.eval('PADA("wc-party")')]


W.execute('PADA = PADA; rawget(_G, "WowCompanionPartyPad").a["wc-pmenu"] = true')
flip(W, "WowCompanionPartyOn")
check(party_on_all(W) == [True] * 9 and W.eval('PADA("wc-pmenu")') is None, "ON: wc-party on all 8 router buttons and the pad; wc-pmenu cleared",
      party_on_all(W))
flip(W, "WowCompanionPartyOn")
check(party_on_all(W) == [True] * 9, "ON again: still on (absolute)")
W.execute('rawget(_G, "WowCompanionPartyPad").a["wc-pmenu"] = true')
flip(W, "WowCompanionPartyOff")
check(party_on_all(W) == [None] * 9 and W.eval('PADA("wc-pmenu")') is None, "OFF: wc-party nil everywhere; wc-pmenu cleared", party_on_all(W))
flip(W, "WowCompanionPartyOff")
check(party_on_all(W) == [None] * 9, "OFF again: still off (absolute)")
W.execute('clickName("WowCompanionPartyToggle", true); clickName("WowCompanionPartyToggle", false); advance(0.3)')
check(party_on_all(W) == [True] * 9, "TOGGLE from off: on (its key-down only: the up does not toggle back)", party_on_all(W))
W.execute('rawget(_G, "WowCompanionPartyPad").a["wc-pmenu"] = true; clickName("WowCompanionPartyToggle", true); clickName("WowCompanionPartyToggle", false); advance(0.3)')
check(party_on_all(W) == [None] * 9 and W.eval('PADA("wc-pmenu")') is None, "TOGGLE from on: off, wc-pmenu cleared", party_on_all(W))
W.execute('COMBAT = true')
W.execute('clickName("WowCompanionPartyToggle", true); NOW = NOW + 0.3')
check(party_on_all(W) == [True] * 9 and W.eval('#VIOL') == 0, "TOGGLE in a fight: on (secure snippets only, no lockdown breach)",
      list(W.eval('VIOL').values()))
W.execute('COMBAT = false')
flip(W, "WowCompanionPartyOff")
d = docs(W, 0, 'partybox')
check(len(d) >= 5 and [x['b']['k'] for x in d[-3:]] == ['off', 'on', 'off'], "every flip sends ONE partybox (k on / off)",
      [x['b']['k'] for x in d])
# m129 ONE PICK (party_design.md A.2): PARTYFLIP "on" clamps wc-box and sets wc-u from it (the ring never empty or stale after a flip)
W.execute('UNITS.party1 = { name = "A", alive = true }; UNITS.party2 = { name = "B", alive = true }; '
          'local p = rawget(_G, "WowCompanionPartyPad"); p.a["wc-box"] = 5; p.a["wc-u"] = "party4"')
flip(W, "WowCompanionPartyOn")
check(W.eval('PADA("wc-box")') == 3 and W.eval('PADA("wc-u")') == "party2" and W.eval('PADA("wc-cnt")') == 3,
      "m129: ON with a stale box 5 / party4 and three members: box 3, wc-u party2, cnt 3", (W.eval('PADA("wc-box")'), W.eval('PADA("wc-u")')))
W.execute('rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 2; rawget(_G, "WowCompanionPartyPad").a["wc-u"] = nil')
flip(W, "WowCompanionPartyOn")
check(W.eval('PADA("wc-box")') == 2 and W.eval('PADA("wc-u")') == "party1", "m129: ON again keeps the box (2) and sets its unit (party1)",
      (W.eval('PADA("wc-box")'), W.eval('PADA("wc-u")')))
# m129: the roster's state driver (in a fight too): party2 leaves, the pick on it re-clamps to party1 and wc-u follows
cnt = W.eval('rawget(_G, "WowCompanionPartyCount")')
check(cnt is not None and cnt.refs['wc-pad'] is not None
      and W.eval('(function() for _, d in ipairs(DRIVERS) do if d.f == rawget(_G, "WowCompanionPartyCount") and d.st == "cnt" then return d.cond end end end)()')
      == "[@party4,exists]5;[@party3,exists]4;[@party2,exists]3;[@party1,exists]2;1",
      "m129: WowCompanionPartyCount: a state driver on the roster (the design's conditions), a ref to the pad", W.eval('#DRIVERS'))
W.execute('driveStates(); rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 3; rawget(_G, "WowCompanionPartyPad").a["wc-u"] = "party2"')
W.execute('COMBAT = true; UNITS.party2 = nil; driveStates()')
check(W.eval('PADA("wc-box")') == 2 and W.eval('PADA("wc-u")') == "party1" and W.eval('PADA("wc-cnt")') == 2 and W.eval('#VIOL') == 0,
      "m129: party2 leaves IN A FIGHT: the driver re-clamps box 2 / party1 (secure, no lockdown breach)",
      (W.eval('PADA("wc-box")'), W.eval('PADA("wc-u")'), list(W.eval('VIOL').values())[:2]))
W.execute('COMBAT = false; UNITS.party1 = nil; driveStates(); rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 1')
flip(W, "WowCompanionPartyOff")
check(W.eval('#SNIPERR') == 0, "PARTYFLIP / the roster's snippet run in the restricted environment without an error", W.eval('SNIPERR[1]'))

# ================================================================ 2. PARTYPAD
# m134 THE PLAYER / PET ROW (party_design.md "PLAYER / PET REVISION"; the user, 2026-11-11, after using 1428: "I think the pet should be
# to the right of the player"): the first row is you alone, or you + the pet on your right; the party members fill the rows under it by
# the adaptive rule among themselves (1-2: full rows; 3: a full row then a shared one; 4: 2x2). THE TABLE IS BUILT FROM THE GEOMETRY
# HERE (an independent statement of the rule, never the add-on's code): every cell has a row and a column span (full = 0..2, left 0..1,
# right 1..2; you / the pet are the first row's left / right); Left / Right = the cell touching it in its row; Up / Down = the cells of
# the next row that overlap its span, the leftmost (from a full cell into a shared row: its left). No wrap.
def nav_rows(n, pet):
    head = [1, 2] if pet else [1]
    k = n - len(head)
    p = list(range(len(head) + 1, n + 1))
    full = k if k <= 2 else 4 - k
    body = [[x] for x in p[:full]] + [p[i:i + 2] for i in range(full, k, 2)]
    return [head] + body


def nav_grid(n, pet):
    out = {}
    for r, row in enumerate(nav_rows(n, pet)):
        if len(row) == 1:
            out[row[0]] = (r, 0, 2)
        else:
            out[row[0]] = (r, 0, 1); out[row[1]] = (r, 1, 2)
    assert sorted(out) == list(range(1, n + 1))
    return out


def nav_geo(n, pet, fr, d):
    g = nav_grid(n, pet)
    r, lo, hi = g[fr]
    if d in ("left", "right"):
        for k, (r2, lo2, hi2) in g.items():
            if r2 == r and ((d == "left" and hi2 == lo) or (d == "right" and lo2 == hi)):
                return k
        return None
    tr = r - 1 if d == "up" else r + 1
    cand = sorted((lo2, k) for k, (r2, lo2, hi2) in g.items() if r2 == tr and lo2 < hi and hi2 > lo)
    return cand[0][1] if cand else None


DIRS = ("up", "down", "left", "right")
DPOS = {"up": 2, "down": 4, "left": 1, "right": 3}
# every shape: n 1..5 with no pet (you + 0..4 party), n 2..6 with the pet (you + pet + 0..4 party)
SHAPES = [(n, pet) for n in range(1, 7) for pet in (False, True) if (n - 1 - (1 if pet else 0)) in range(0, 5)]
check(len(SHAPES) == 10, "m131: 10 shapes (n 1 no pet .. n 6 with the pet)", SHAPES)
NAV = {(n, pet, fr): tuple(nav_geo(n, pet, fr, d) for d in DIRS) for n, pet in SHAPES for fr in range(1, n + 1)}
# the user's own words, spelled out
check([nav_rows(n, False) for n in range(1, 6)] == [[[1]], [[1], [2]], [[1], [2], [3]], [[1], [2], [3, 4]], [[1], [2, 3], [4, 5]]]
      and [nav_rows(n, True) for n in range(2, 7)] == [[[1, 2]], [[1, 2], [3]], [[1, 2], [3], [4]], [[1, 2], [3], [4, 5]],
                                                       [[1, 2], [3, 4], [5, 6]]],
      "m134: the rows: you (+ the pet on your right) first, the party under it (1-2 full rows, 3: full + shared, 4: 2x2)")
check(NAV[(4, False, 2)] == (1, 3, None, None) and NAV[(4, False, 4)] == (2, None, 3, None) and NAV[(5, False, 1)] == (None, 2, None, None)
      and NAV[(5, False, 3)] == (1, 5, 2, None) and NAV[(2, True, 1)] == (None, None, None, 2) and NAV[(3, True, 2)] == (None, 3, 1, None)
      and NAV[(3, True, 3)] == (1, None, None, None) and NAV[(6, True, 2)] == (None, 4, 1, None) and NAV[(6, True, 4)] == (2, 6, 3, None)
      and NAV[(5, True, 5)] == (3, None, 4, None),
      "m134: the table's spot checks (pet: you Right = the pet, the pet Down = party1, party1 Up = you; 2x2 under you + pet: same side)")
NAVL = lupa.LuaRuntime(unpack_returned_tuples=True)
NAVL.execute('OV = {}\n' + re.search(r'-- PARTY-NAV-BEGIN[^\n]*\n(.*?)-- PARTY-NAV-END', SRC["padmap"], re.S).group(1))
nav = NAVL.eval('OV.partyNav')
bad = []
for (n, pet, fr), want in NAV.items():
    for dname, w in zip(DIRS, want):
        got = nav(n, fr, dname, pet)
        if got != (w if w is not None else fr):
            bad.append((n, pet, fr, dname, got))
check(not bad, "m134: OV.partyNav = the geometric table, all %d (shape, box) x 4 directions" % len(NAV), bad[:4])
nav_lua = re.search(r'-- PARTY-NAV-BEGIN.*?OV\.partyNav = function\(n, box, dir, pet\)\n(.*?)\n\s*return nb\n\s*end\n\s*-- PARTY-NAV-END', SRC_PM := open(F["padmap"], encoding="utf-8").read(), re.S)
nav_snip = re.search(r'-- PARTY-NAV-BODY-BEGIN\n(.*?)\n\s*-- PARTY-NAV-BODY-END', SRC_PM, re.S)
norm = lambda t: re.sub(r'\s+', ' ', t).strip()
check(nav_lua is not None and nav_snip is not None and norm(nav_lua.group(1)) == norm(nav_snip.group(1)),
      "the PARTYPAD snippet carries the same NAV body as OV.partyNav")
# the app's JVM test reads the same table (PartyModelTest: PartyModel.nav must equal it for every shape): kept with the fixtures
NAV_FIXTURE = json.dumps({"rows": [{"n": n, "pet": pet, "box": fr, "up": w[0], "down": w[1], "left": w[2], "right": w[3]}
                                   for (n, pet, fr), w in sorted(NAV.items())]}, separators=(",", ":"), sort_keys=True)
# the snippet itself through the router (Party mode on), every SHAPE: n = 1..6 with the pet out and without (n 6 has the pet; n 1 none)
flip(W, "WowCompanionPartyOn")
MEMBERS = [["Brannoc", "WARRIOR", True, "TANK"], ["Iselle", "MAGE"], ["Doran", "ROGUE", True, "DAMAGER", "ENERGY"], ["Mira", "PRIEST", True, "HEALER", "MANA"]]


def shape(L, n, pet):
    L.eval('party')(L.table_from([L.table_from(m) for m in MEMBERS[:n - 1 - (1 if pet else 0)]]))
    L.execute('UNITS.pet = %s' % ('{ name = "Wolf", alive = true }' if pet else 'nil'))


def units_of(n, pet):
    return ["player"] + (["pet"] if pet else []) + ["party%d" % i for i in range(1, n - (1 if pet else 0))]


bad = []
for n, pet in SHAPES:
    shape(W, n, pet)
    us = units_of(n, pet)
    for fr in range(1, n + 1):
        for dname, w in zip(DIRS, NAV[(n, pet, fr)]):
            W.execute('rawget(_G, "WowCompanionPartyPad").a["wc-box"] = %d' % fr)
            a0 = nact(W)
            W.execute('hit(%d)' % DPOS[dname])
            got = W.eval('PADA("wc-box")')
            want = w if w is not None else fr
            # m134 HIGHLIGHTING = TARGETING: a move that moved targets the new entry in the same click; an edge press targets nothing
            tgt = [("target", None, us[want - 1])] if w is not None else []
            if got != want or W.eval('PADA("wc-u")') != us[want - 1] or acts(W, a0) != tgt or W.eval('PADA("wc-k")') != "move" \
                    or W.eval('PADA("wc-cnt")') != n:
                bad.append((n, pet, fr, dname, got, W.eval('PADA("wc-u")'), W.eval('PADA("wc-k")'), acts(W, a0)))
check(not bad, "m134: PARTYPAD's D-pad = the table in all 10 shapes (k move, wc-u the entry: you, pet, party1..); a move TARGETS the new "
      "entry in the same click, an edge press nothing", bad[:4])
W.execute('UNITS.pet = nil')
# m129 ONE PICK: every move sets wc-u with wc-box
W.eval('party')(W.table_from([W.table_from(m) for m in MEMBERS[:3]]))
W.execute('rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 1; rawget(_G, "WowCompanionPartyPad").a["wc-u"] = nil; hit(4); hit(4)')
check(W.eval('PADA("wc-box")') == 3 and W.eval('PADA("wc-u")') == "party2", "m131: four entries, Down twice: box 3 (the bottom row's left), wc-u party2",
      (W.eval('PADA("wc-box")'), W.eval('PADA("wc-u")')))
W.execute('hit(3)')
check(W.eval('PADA("wc-box")') == 4 and W.eval('PADA("wc-u")') == "party3" and W.eval('PADA("wc-k")') == "move",
      "m131: Right in the shared row: box 4, party3 (k move)", (W.eval('PADA("wc-box")'), W.eval('PADA("wc-k")')))
W.execute('hit(3)')
check(W.eval('PADA("wc-box")') == 4, "m131: Right at the row's right: no wrap", W.eval('PADA("wc-box")'))
W.execute('party({}); rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 4')
W.execute('hit(2)')
check(W.eval('PADA("wc-box")') == 1, "a box past n (someone left) clamps to n", W.eval('PADA("wc-box")'))
check(W.eval('#SNIPERR') == 0, "PARTYPAD runs in the restricted environment without an error", W.eval('SNIPERR[1]'))
# m131 THE PET IS A PICK TARGET: A casts on "pet"; Y with nothing to cast on the pet does nothing (no player menu for a pet)
W.eval('party')(W.table_from([W.table_from(m) for m in MEMBERS[:1]]))
W.execute('UNITS.pet = { name = "Wolf", alive = true }; local p = rawget(_G, "WowCompanionPartyPad"); p.a["wc-box"] = 2; '
          'p.a["wc-ps-a"] = "Healing Wave"; p.a["wc-pmenu"] = nil')
a0 = nact(W)
W.execute('hit(8)')
check(acts(W, a0) == [("spell", "Healing Wave", "pet")] and W.eval('PADA("wc-u")') == "pet", "m131: box 2 with the pet out = the pet: A casts on \"pet\"",
      acts(W, a0))
a0 = nact(W)
W.execute('hit(6)')
check(acts(W, a0) == [] and W.eval('PADA("wc-k")') == "none" and W.eval('PADA("wc-pmenu")') is None,
      "m131: Y on the pet with no Smart Y pick: nothing (no menu sub-state)", (acts(W, a0), W.eval('PADA("wc-k")')))
W.execute('rawget(_G, "WowCompanionPartyPad").a["wc-pb-pet"] = "Water Breathing"')
a0 = nact(W)
W.execute('hit(6)')
check(acts(W, a0) == [("spell", "Water Breathing", "pet")], "m131: Y on the pet with Smart Y's pick for it: cast on the pet", acts(W, a0))
W.execute('local p = rawget(_G, "WowCompanionPartyPad"); p.a["wc-pb-pet"] = nil; p.a["wc-ps-a"] = nil')
# m131: the roster's driver on the pet: the pet goes in a fight - the pick on party1 (box 3) KEEPS party1 (box 2 now); the pet comes back:
# box 3 again, still party1; the pick ON the pet when it goes: clamped (box 2 = party1)
cnt = W.eval('rawget(_G, "WowCompanionPartyCount")')
check(W.eval('(function() for _, d in ipairs(DRIVERS) do if d.f == rawget(_G, "WowCompanionPartyCount") and d.st == "pet" then return d.cond end end end)()')
      == "[@pet,exists]1;0" and cnt.a['_onstate-pet'] == cnt.a['_onstate-cnt'],
      "m131: the roster's frame has a second driver on the pet ([@pet,exists]1;0), the same snippet")
W.execute('driveStates(); local p = rawget(_G, "WowCompanionPartyPad"); p.a["wc-box"] = 3; p.a["wc-u"] = "party1"')
W.execute('COMBAT = true; UNITS.pet = nil; driveStates()')
check(W.eval('PADA("wc-box")') == 2 and W.eval('PADA("wc-u")') == "party1" and W.eval('PADA("wc-cnt")') == 2 and W.eval('#VIOL') == 0,
      "m131: the pet dismissed IN A FIGHT: the pick stays on party1 (box 3 -> 2), no lockdown breach",
      (W.eval('PADA("wc-box")'), W.eval('PADA("wc-u")'), list(W.eval('VIOL').values())[:2]))
W.execute('UNITS.pet = { name = "Wolf", alive = true }; driveStates()')
check(W.eval('PADA("wc-box")') == 3 and W.eval('PADA("wc-u")') == "party1" and W.eval('PADA("wc-cnt")') == 3,
      "m131: the pet back: still party1 (box 3)", (W.eval('PADA("wc-box")'), W.eval('PADA("wc-u")')))
W.execute('local p = rawget(_G, "WowCompanionPartyPad"); p.a["wc-box"] = 2; p.a["wc-u"] = "pet"; UNITS.pet = nil; driveStates()')
check(W.eval('PADA("wc-box")') == 2 and W.eval('PADA("wc-u")') == "party1", "m131: the picked pet gone: the box clamps (2 = party1 now)",
      (W.eval('PADA("wc-box")'), W.eval('PADA("wc-u")')))
W.execute('COMBAT = false; party({}); UNITS.pet = nil; driveStates(); rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 1')
check(W.eval('#SNIPERR') == 0, "the pet's driver runs in the restricted environment without an error", W.eval('SNIPERR[1]'))


def setps(L, **kv):
    L.execute('local p = rawget(_G, "WowCompanionPartyPad"); for _, k in ipairs({"a","x","zla","zlx","zly","zra","zrx","zry"}) do p.a["wc-ps-" .. k] = nil end')
    for k, v in kv.items():
        L.execute('rawget(_G, "WowCompanionPartyPad").a["%s"] = %s' % ("wc-ps-" + k, json.dumps(v)))


W.eval('party')(W.table_from([W.table_from(m) for m in MEMBERS[:2]]))
setps(W, a="Healing Wave", x="Lesser Healing Wave", zla="Cure Poison", zlx="Cure Disease", zra="Ancestral Spirit")
W.execute('rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 1; hit(4)')         # box 2 = party1
cases = [((False, False), 8, ("spell", "Healing Wave", "party1")), ((False, False), 5, ("spell", "Lesser Healing Wave", "party1")),
         ((True, False), 8, ("spell", "Cure Poison", "party1")), ((True, False), 5, ("spell", "Cure Disease", "party1")),
         ((False, True), 8, ("spell", "Ancestral Spirit", "party1"))]
for (lt, rt), pos, want in cases:
    W.execute('trig(%s, %s)' % ('true' if lt else 'false', 'true' if rt else 'false'))
    a0 = nact(W)
    W.execute('hit(%d)' % pos)
    got = acts(W, a0)
    check(got == [want], "%s%s: casts %s on %s, once" % ({(False, False): "", (True, False): "ZL+", (False, True): "ZR+"}[(lt, rt)],
                                                          {8: "A", 5: "X"}[pos], want[1], want[2]), got)
for lt, rt, pos, why in ((False, True, 5, "ZR+X Empty"), (True, False, 6, "ZL+Y Empty"), (True, True, 8, "ZL+ZR+A (both triggers)")):
    W.execute('trig(%s, %s)' % ('true' if lt else 'false', 'true' if rt else 'false'))
    a0 = nact(W)
    W.execute('hit(%d)' % pos)
    check(acts(W, a0) == [] and W.eval('PADA("wc-k")') == "none", why + ": nothing", (acts(W, a0), W.eval('PADA("wc-k")')))
W.execute('trig(false, false); party({}); rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 1')
a0 = nact(W)
W.execute('hit(8)')
check(acts(W, a0) == [("spell", "Healing Wave", "player")], "solo: A casts on yourself (player)", acts(W, a0))
# the key-up changes nothing (whichever edge the template acts on)
W.execute('ACT_ON_DOWN = false')
a0 = nact(W)
W.execute('hit(8)')
check(acts(W, a0) == [("spell", "Healing Wave", "player")], "the template acting on the RELEASE: still one cast, the down's", acts(W, a0))
W.execute('ACT_ON_DOWN = true')
W.execute('local b = router(8); doClick(b, "LeftButton", true)')
before = dict(W.eval('rawget(_G, "WowCompanionPartyPad")').a)
W.execute('local b = router(8); doClick(b, "LeftButton", false)')
after = dict(W.eval('rawget(_G, "WowCompanionPartyPad")').a)
check(before == after, "the key-up leaves every pad attribute as the key-down set it")

# Y out of combat: Smart Y's pick on a living member; dead / no pick -> the menu
W.eval('party')(W.table_from([W.table_from(m) for m in MEMBERS[:2]]))
W.execute('local p = rawget(_G, "WowCompanionPartyPad"); p.a["wc-box"] = 2; p.a["wc-pb-party1"] = "Water Breathing"; p.a["wc-pmenu"] = nil')
a0 = nact(W)
W.execute('hit(6)')
check(acts(W, a0) == [("spell", "Water Breathing", "party1")] and W.eval('PADA("wc-k")') == "cast", "Y out of combat: Smart Y's pick on the member",
      acts(W, a0))
W.execute('UNITS.party1.alive = false')
a0 = nact(W)
W.execute('hit(6)')
check(acts(W, a0) == [] and W.eval('PADA("wc-k")') == "menu" and W.eval('PADA("wc-pmenu")') is True and W.eval('PADA("wc-u")') == "party1",
      "Y out of combat, the member dead: the player menu (wc-pmenu, wc-u)", (acts(W, a0), W.eval('PADA("wc-k")')))
W.execute('UNITS.party1.alive = true; rawget(_G, "WowCompanionPartyPad").a["wc-pmenu"] = nil; rawget(_G, "WowCompanionPartyPad").a["wc-pb-party1"] = nil')
a0 = nact(W)
W.execute('hit(6)')
check(acts(W, a0) == [] and W.eval('PADA("wc-k")') == "menu", "Y out of combat, no pick: the menu", W.eval('PADA("wc-k")'))
# the menu sub-state swallows every press as "key"
W.execute('rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 2')
bad = []
for pos in (1, 2, 3, 4, 8, 5, 6, 7):
    for lt, rt in ((False, False), (True, False)):
        W.execute('trig(%s, %s)' % ('true' if lt else 'false', 'true' if rt else 'false'))
        a0 = nact(W)
        W.execute('hit(%d)' % pos)
        if acts(W, a0) != [] or W.eval('PADA("wc-k")') != "key" or W.eval('PADA("wc-box")') != 2 or W.eval('PADA("wc-party")') is not True:
            bad.append((pos, lt, W.eval('PADA("wc-k")')))
check(not bad, "the menu sub-state: D-pad, A, X, Y, B and chords are each a \"key\" press (no game action, box kept, Party mode kept)", bad[:3])
W.execute('trig(false, false)')
W.execute('hit(2)')
check(W.eval('PADA("wc-key")') == "PADDUP", "a key press says which key (wc-key PADDUP)", W.eval('PADA("wc-key")'))
d = docs(W, 0, 'partybox')[-1]['b']
check(d['k'] == 'key' and d['key'] == 'PADDUP' and d['pm'] is True and d['box'] == 2, "its partybox: k key, key PADDUP, pm true", d)
flip(W, "WowCompanionPartyMenuDone")
check(W.eval('PADA("wc-pmenu")') is None and W.eval('PADA("wc-party")') is True, "MENU DONE clears the sub-state (Party mode stays on)")
# Y in combat: wc-py on a help,nodead member; m132 (the user, a healer: usable in combat): NEVER the player menu in a fight (its
# sub-state held every press until MENU DONE) - no In combat action: Smart Y's party buff armed for the member before the fight, else Target
W.execute('COMBAT = true; rawget(_G, "WowCompanionPartyPad").a["wc-py"] = "Healing Wave"; rawget(_G, "WowCompanionPartyPad").a["wc-pb-party1"] = "Water Breathing"')
a0 = nact(W)
W.execute('hit(6)')
check(acts(W, a0) == [("spell", "Healing Wave", "party1")], "Y in combat: its in-combat spell on the member (before Smart Y's buff)", acts(W, a0))
for why, setup in (("dead", 'UNITS.party1.alive = false'), ("not assistable", 'UNITS.party1.foe = true')):
    W.execute('UNITS.party1.alive = true; UNITS.party1.foe = nil; rawget(_G, "WowCompanionPartyPad").a["wc-pmenu"] = nil; ' + setup)
    a0 = nact(W)
    W.execute('hit(6)')
    check(acts(W, a0) == [("target", None, "party1")] and W.eval('PADA("wc-k")') == "target" and W.eval('PADA("wc-pmenu")') is None,
          "m132: Y in combat, the member " + why + ": Target it (no menu sub-state)", (acts(W, a0), W.eval('PADA("wc-k")')))
W.execute('UNITS.party1.alive = true; UNITS.party1.foe = nil; local p = rawget(_G, "WowCompanionPartyPad"); p.a["wc-pmenu"] = nil; p.a["wc-py"] = nil')
a0 = nact(W)
W.execute('hit(6)')
check(acts(W, a0) == [("spell", "Water Breathing", "party1")] and W.eval('PADA("wc-k")') == "cast",
      "m132: Y in combat, no In combat action: Smart Y's party buff armed before the fight (wc-pb-party1) on the member", acts(W, a0))
W.execute('rawget(_G, "WowCompanionPartyPad").a["wc-pb-party1"] = nil')
a0 = nact(W)
W.execute('hit(6)')
check(acts(W, a0) == [("target", None, "party1")] and W.eval('PADA("wc-k")') == "target" and W.eval('PADA("wc-pmenu")') is None,
      "m132: Y in combat, nothing armed: Target the member, never the menu", (acts(W, a0), W.eval('PADA("wc-k")')))
W.execute('rawget(_G, "WowCompanionPartyPad").a["wc-pmenu"] = nil')
# B = Party mode off on all 8 refs, in a fight too
W.execute('hit(7)')
check(party_on_all(W) == [None] * 9 and W.eval('PADA("wc-k")') == "off" and W.eval('#VIOL') == 0, "B: Party mode off on all 8 router buttons and the pad (in a fight)",
      party_on_all(W))
a0 = nact(W)
W.execute('hit(8)')
check(W.eval('PADA("wc-k")') == "off" and all(a[0] != "spell" or a[2] is None for a in acts(W, a0)),
      "after B the next A is the router's own again (not the pad's)", acts(W, a0))
W.execute('COMBAT = false')
check(W.eval('#SNIPERR') == 0 and W.eval('#SPY') == 0, "no snippet error, no spied call", (W.eval('SNIPERR[1]'), W.eval('SPY[1]')))

# ================================================================ 4. THE BINDINGS (the binding table is a spy: only overrides on our owners)
OURKEYS = ["PAD1", "PAD2", "PAD3", "PAD4", "PADDLEFT", "PADDUP", "PADDRIGHT", "PADDDOWN", "PADBACK", "SHIFT-PAD1", "PADFORWARD"]


def keys(L):
    b = dict(L.eval('BIND'))
    return {k: v for k, v in b.items() if k in OURKEYS}


def router_on(L):
    return {k: "CLICK %s:LeftButton" % ROUTER_NAME[KEY_POS[k]] for k in KEY_POS}


W4 = world(pre='WowCompanionSpellsDB.binds = { ["Korvash-Realm"] = { ["2:8"] = "JUMP", ["1:1"] = "TOGGLEAUTORUN" } }')
k = keys(W4)
check(k.get("PADBACK") == "CLICK WowCompanionPartyToggle:LeftButton" and k.get("SHIFT-PAD1") == "JUMP" and k.get("PADDLEFT") == "TOGGLEAUTORUN"
      and "PAD1" not in k, "Party mode OFF, out of combat: Select -> the toggle; today's plan otherwise (the m34 chords bound)", k)
W4.execute('tapKey("PADBACK"); advance(0.3)')
k = keys(W4)
want = router_on(W4)
want["PADBACK"] = "CLICK WowCompanionPartyToggle:LeftButton"
check(W4.eval('PADA("wc-party")') is True and k == want, "Select ON: all 8 router keys carried + Select; NO m34 chord (SHIFT-PAD1, PADDLEFT)", k)
W4.execute('NOW = NOW + 2; tick(0.1)')
check(keys(W4) == want, "routerCheck keeps it (nothing lost)", keys(W4))
W4.execute('BIND["PAD3"] = "INTERACTTARGET"; NOW = NOW + 2; tick(0.1)')
check(keys(W4).get("PAD3") == "CLICK WowCompanionCombatFace5:LeftButton", "a key taken from us is bound again by routerCheck", keys(W4).get("PAD3"))
# the whole road by keys: D-pad Down, A, B
W4.eval('party')(W4.table_from([W4.table_from(m) for m in MEMBERS[:2]]))
setps(W4, a="Healing Wave")
W4.execute('rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 1')
a0 = nact(W4)
W4.execute('tapKey("PADDDOWN"); tapKey("PAD1")')
check(acts(W4, a0) == [("target", None, "party1"), ("spell", "Healing Wave", "party1")],
      "by keys: Down boxes AND targets party1 (m134), A heals party1", acts(W4, a0))
W4.execute('tapKey("PAD2"); advance(0.3)')
k = keys(W4)
check(W4.eval('PADA("wc-party")') is None and k.get("SHIFT-PAD1") == "JUMP" and k.get("PADDLEFT") == "TOGGLEAUTORUN" and "PAD1" not in k
      and k.get("PADBACK") == "CLICK WowCompanionPartyToggle:LeftButton", "B: off, today's plan back (chords bound, A the game's)", k)
# an unfocused dialog keeps Select the game's; routerCheck follows it
W4.execute('UNFOCUSED = 1; NOW = NOW + 2; tick(0.1)')
check("PADBACK" not in keys(W4), "an unfocused dialog waits: Select is left to the game", keys(W4).get("PADBACK"))
W4.execute('UNFOCUSED = 0; NOW = NOW + 2; tick(0.1)')
check(keys(W4).get("PADBACK") == "CLICK WowCompanionPartyToggle:LeftButton", "the dialog gone: Select is the toggle's again")
# THE USER'S RULINGS (INTEGRATION, PM.OFF_WHEN_ASIDE = true): ANYTHING that takes the controller ends Party mode - a room, a Home
# module full screen ("pad:home"), Home's pause wheel ("pad:radial"), the bar cursor ("pad:bar"), a game window (a vendor) with nobody
# holding
check('PM.OFF_WHEN_ASIDE = true' in SRC["padmap"], "PM.OFF_WHEN_ASIDE is true (the user's rulings: anything that takes the controller)")
for holder, win in (("spells", None), ("pad:home", None), ("pad:radial", None), ("pad:bar", None), (None, "MerchantFrame"), (None, "LootFrame")):
    what = holder or win
    W4.execute('if not PADA("wc-party") then tapKey("PADBACK"); advance(0.3) end')
    on0 = W4.eval('PADA("wc-party")')
    n0 = nsent(W4)
    if holder:
        W4.execute('HOLDER = "%s"' % holder)
    else:
        W4.execute('%s = { IsShown = function() return true end }' % win)
    W4.execute('NOW = NOW + 2; tick(0.1); advance(0.6)')
    pd = docs(W4, n0, 'party')
    check(on0 is True and party_on_all(W4) == [None] * 9 and pd and pd[-1]['p']['on'] is False
          and not any(v.startswith("CLICK WowCompanion") for v in keys(W4).values()),
          "%s takes the controller: Party mode OFF on all 8 refs and the pad, a party document says on false, nothing of ours bound" % what,
          (on0, party_on_all(W4), pd and pd[-1]['p']))
    W4.execute('HOLDER = nil; %s NOW = NOW + 2; tick(0.1)' % (('%s = nil;' % win) if win else ''))
    k = keys(W4)
    check(W4.eval('PADA("wc-party")') is None and k.get("SHIFT-PAD1") == "JUMP" and "PAD1" not in k
          and k.get("PADBACK") == "CLICK WowCompanionPartyToggle:LeftButton",
          "%s let go: Party mode is still OFF (today's plan, Select the toggle's)" % what, k)
# m132 A PROMPT ONLY SUSPENDS (out of combat): a loot roll, a static popup, a ready check steps the router aside (A answers it) but
# Party mode stays on and is back, every key ours, when it closes - also when the prompt is one of the controller manager's frames
for win in ("GroupLootFrame1", "StaticPopup1", "StaticPopup4", "ReadyCheckFrame", "managed:StaticPopup2",
            "managed:GamepadGroupLootRollFrame", "managed:GroupLootFrame2", "holder:dialogs"):
    managed = win.startswith("managed:")
    holder = win.startswith("holder:")
    name = win.split(":")[-1]
    W4.execute('if not PADA("wc-party") then tapKey("PADBACK"); advance(0.3) end')
    if holder:
        W4.execute('HOLDER = "%s"' % name)
    else:
        W4.execute('%s = { IsShown = function() return true end }' % name)
    if managed:
        W4.execute('GamepadMode = { FrameControlsManager = { shownFrames = { %s } } }' % name)
    n0 = nsent(W4)
    W4.execute('NOW = NOW + 2; tick(0.1); advance(0.6)')
    pd = docs(W4, n0, 'party')
    check(W4.eval('PADA("wc-party")') is True and pd and pd[-1]['p']['on'] is True and pd[-1]['p'].get('aside')
          and not any(v.startswith("CLICK WowCompanion") for v in keys(W4).values()),
          "m132: %s out of combat: SUSPENDED (router aside, nothing of ours bound) but Party mode stays on" % win,
          (W4.eval('PADA("wc-party")'), pd and pd[-1]['p'], keys(W4)))
    W4.execute(('HOLDER = nil' if holder else '%s = nil' % name) + '; GamepadMode = nil; NOW = NOW + 2; tick(0.1); advance(0.6)')
    check(W4.eval('PADA("wc-party")') is True and keys(W4) == want, "m132: %s closed: Party mode live again, all 8 keys + Select" % win, keys(W4))
W4.execute('tapKey("PADBACK"); advance(0.3)')
# IN A FIGHT (m132, the user, a healer: "Party mode needs to be usable in combat for healers. Locking it is retarded"): NOTHING turns it
# off. (a) party col 11 (a secure click: the bottom tab's off) still ends it at once, with no lockdown breach
W4.execute('tapKey("PADBACK"); advance(0.3); fire("PLAYER_REGEN_DISABLED"); COMBAT = true; HOLDER = "spells"')
W4.execute('local c = WowCompanion.pad.rows.party.cells[11]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false); NOW = NOW + 0.3')
k = keys(W4)
check(party_on_all(W4) == [None] * 9 and W4.eval('#VIOL') == 0 and k.get("PAD1") == "CLICK WowCompanionCombatFace8:LeftButton",
      "a tap on party col 11 in a fight: OFF at once (secure, no breach); A stays the fight router's", k.get("PAD1"))
W4.execute('HOLDER = nil; COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1); NOW = NOW + 2; tick(0.1)')
check(W4.eval('PADA("wc-party")') is None and "PAD1" not in keys(W4), "... the fight ends: still off, today's plan", keys(W4).get("PAD1"))
# (b) a room holds in the fight (its 1 s tick, the capture's change): Party mode stays set through the fight and after it
W4.execute('tapKey("PADBACK"); advance(0.3); fire("PLAYER_REGEN_DISABLED"); COMBAT = true; HOLDER = "spells"; tick(1)')
check(W4.eval('PADA("wc-party")') is True and W4.eval('#VIOL') == 0, "m132: a room holds in a fight: Party mode still set (suspended only)",
      list(W4.eval('VIOL').values())[:2])
W4.execute('HOLDER = nil; tick(1)')
n0 = nsent(W4)
W4.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1); NOW = NOW + 2; tick(0.1)')
pd = docs(W4, n0, 'party')
check(party_on_all(W4) == [True] * 9 and all(x['p']['on'] is True for x in pd) and keys(W4) == want,
      "m132: ... the fight ends (the room let go): STILL ON, no party document says off, its keys bound", (pd and pd[-1]['p'], keys(W4) == want))
# (c) a game window seen in the fight (a loot window): still on at the fight's end
W4.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; LootFrame = { IsShown = function() return true end }; tick(1)')
W4.execute('LootFrame = nil; COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1); NOW = NOW + 2; tick(0.1)')
check(W4.eval('PADA("wc-party")') is True, "m132: a loot window in a fight: still ON at the fight's end", W4.eval('PADA("wc-party")'))
W4.execute('tapKey("PADBACK"); advance(0.3)')
# (d) nothing took the controller: a fight leaves Party mode on
W4.execute('tapKey("PADBACK"); advance(0.3); fire("PLAYER_REGEN_DISABLED"); COMBAT = true; tick(1)')
W4.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1); NOW = NOW + 2; tick(0.1)')
check(W4.eval('PADA("wc-party")') is True and keys(W4) == want, "a fight with no taker: Party mode stays on, its keys bound", keys(W4))
W4.execute('tapKey("PADBACK"); advance(0.3)')
# a fight's start: m132 Select ALWAYS while Party mode is allowed (solo too); solo and off, the player's own chords stay (nothing to
# turn on for), grouped they give way to the party layers
W4.eval('party')(W4.table_from([]))
W4.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true')
k = keys(W4)
check(k.get("PADBACK") == "CLICK WowCompanionPartyToggle:LeftButton" and k.get("PAD2") == "CLICK WowCompanionFaceB:LeftButton"
      and k.get("SHIFT-PAD1") == "JUMP", "m132: a fight's start, solo, Party mode off: Select carried to the toggle; the router carries its keys; chords kept", k)
W4.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
W4.eval('party')(W4.table_from([W4.table_from(MEMBERS[0])]))
W4.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true')
k = keys(W4)
check(k.get("PADBACK") == "CLICK WowCompanionPartyToggle:LeftButton" and "SHIFT-PAD1" not in k and "PADDLEFT" in k
      and k["PADDLEFT"].startswith("CLICK WowCompanion"),
      "a fight's start, grouped: Select carried to the toggle; m132: no m34 chord (SHIFT-PAD1 / PADDLEFT) over the party layers", k)
W4.execute('tapKey("PADBACK")')
check(W4.eval('PADA("wc-party")') is True and W4.eval('#VIOL') == 0, "in the fight Select switches Party mode on (secure only)", list(W4.eval('VIOL').values()))
a0 = nact(W4)
W4.execute('rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 1; tapKey("PADDDOWN"); tapKey("PAD1")')
check(acts(W4, a0) == [("target", None, "party1"), ("spell", "Healing Wave", "party1")],
      "... and its presses reach the party pad mid-fight (carried keys; the move targets, m134)", acts(W4, a0))
W4.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
W4.eval('party')(W4.table_from([]))
W4.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true')
check(keys(W4).get("PADBACK") == "CLICK WowCompanionPartyToggle:LeftButton", "a fight's start, solo but Party mode already on: Select carried",
      keys(W4).get("PADBACK"))
W4.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
W4.execute('UNFOCUSED = 1; fire("PLAYER_REGEN_DISABLED"); COMBAT = true')
check(keys(W4).get("PADBACK") == "CLICK WowCompanionPartyToggle:LeftButton", "m132: a fight's start with an unfocused dialog: Select still the toggle's",
      keys(W4).get("PADBACK"))
W4.execute('COMBAT = false; UNFOCUSED = 0; fire("PLAYER_REGEN_ENABLED"); advance(1)')
check(W4.eval('#SPY') == 0 and W4.eval('#VIOL') == 0, "the spy: no SetBinding / SaveBindings / macro / CVar / bar write / Blizzard window call; no lockdown breach",
      (list(W4.eval('SPY').values())[:3], list(W4.eval('VIOL').values())[:3]))

# ================================================================ 6. THE STORE AND THE CLASS DEFAULTS
DEFAULTS = {  # PARTY_BUILD.md 3.6
    "SHAMAN": {"a": 331, "x": 8004, "zla": 526, "zlx": 2870, "zra": 2008},
    "PRIEST": {"a": 2050, "x": 17, "zla": 527, "zlx": 528, "zra": 2006, "zrx": 139},
    "DRUID": {"a": 5185, "x": 774, "zla": 8946, "zlx": 2782, "zra": 20484, "zrx": 8936},
    "PALADIN": {"a": 635, "x": 19750, "zla": 1152, "zlx": 4987, "zra": 7328, "zrx": 633},
    "MAGE": {"a": 475, "zla": 604, "zlx": 1008}, "WARLOCK": {"a": 5697}, "HUNTER": {}, "ROGUE": {}, "WARRIOR": {},
}
SLOTS = ["a", "x", "zla", "zlx", "zly", "zra", "zrx", "zry"]


def cfg(L):
    return L.eval('WowCompanion.padMap.party.cfg()')


def slot_ids(c):
    s = c['slots']
    return {k: int(s[k]['id']) for k in SLOTS if s[k] is not None}


W6 = world()
c = cfg(W6)
check(slot_ids(c) == DEFAULTS["SHAMAN"], "a level 6 Shaman with nothing chosen: the class defaults", slot_ids(c))
check(c['slots']['a']['k'] is True and c['slots']['a']['d'] is True and c['slots']['x']['k'] is False and c['slots']['zra']['res'] is True,
      "Healing Wave learned (k), Lesser Healing Wave not yet (k false, drawn dim), Ancestral Spirit res", "")
check(W6.eval('PADA("wc-ps-a")') == "Healing Wave" and W6.eval('PADA("wc-ps-x")') is None and W6.eval('PADA("wc-ps-zra")') is None,
      "the pad casts only learned spells: wc-ps-a Healing Wave; x / zra (not learned) nil", (W6.eval('PADA("wc-ps-x")'),))
bad = []
for cls, want in DEFAULTS.items():
    W6.execute('CLASS = "%s"' % cls)
    got = slot_ids(cfg(W6))
    if got != want:
        bad.append((cls, got))
check(not bad, "the class defaults for all 9 classes (the contract's table)", bad[:2])
W6.execute('CLASS = "SHAMAN"')
n0 = nsent(W6)
ok = W6.eval('WowCompanion.padMap.party.put')("x", False)
check(ok is True and W6.eval('WowCompanionSpellsDB.partyBy["Korvash-Realm"].slots.x') is False and cfg(W6)['slots']['x'] is None,
      "put x = Empty: stored as false (chosen), the document's x is null", W6.eval('WowCompanionSpellsDB.partyBy["Korvash-Realm"].slots.x'))
ack = docs(W6, n0, 'partyack')
check(len(ack) == 1 and ack[0]['op'] == 'slot' and ack[0]['ok'] is True and ack[0]['slot'] == 'x', "answered partyack op slot ok", ack)
check(W6.eval('WowCompanionSpellsDB.partyBy["Korvash-Realm"].slots.a') is None, "a not chosen stays nil (the class default)")
for sid, why in ((324, "Lightning Shield: a self-only buff"), (403, "Lightning Bolt: harmful")):
    n0 = nsent(W6)
    W6.eval('WowCompanion.padMap.party.put')("a", sid)
    ack = docs(W6, n0, 'partyack')
    check(ack and ack[0]['ok'] is False and ack[0]['why'] == 'not a party spell', "a spell not in the chooser is refused (%s)" % why, ack)
W6.execute('BOOK = { 403, 331, 324, 8004 }; fire("SPELLS_CHANGED"); advance(1)')
check(W6.eval('PADA("wc-ps-x")') is None, "x stays Empty when its default is learned later (Empty was CHOSEN)")
W6.eval('WowCompanion.padMap.party.put')("zry", 8004)
check(W6.eval('PADA("wc-ps-zry")') == "Lesser Healing Wave" and int(cfg(W6)['slots']['zry']['id']) == 8004 and cfg(W6)['slots']['zry']['d'] is False,
      "put zry = Lesser Healing Wave (learned now): the pad casts it, d false", W6.eval('PADA("wc-ps-zry")'))
W6.eval('WowCompanion.padMap.party.put')("yc", 331)
W6.eval('WowCompanion.padMap.party.put')("touch", 8004)
check(W6.eval('PADA("wc-py")') == "Healing Wave" and W6.eval('WowCompanionSpellsDB.partyBy["Korvash-Realm"].yc') == 331,
      "yc = Healing Wave: wc-py set, stored by id", W6.eval('PADA("wc-py")'))
W6.eval('WowCompanion.padMap.party.put')("yc", False)
check(W6.eval('PADA("wc-py")') is None and W6.eval('WowCompanionSpellsDB.partyBy["Korvash-Realm"].yc') is None, "yc back to Inspect: nil")
W6.execute('WHO = "Alt"')
c2 = cfg(W6)
check(slot_ids(c2) == DEFAULTS["SHAMAN"] and W6.eval('WowCompanionSpellsDB.partyBy["Alt-Realm"].slots.x') is None
      and W6.eval('WowCompanionSpellsDB.partyBy["Korvash-Realm"].slots.x') is False, "another character (name-realm) never shares: its own defaults",
      slot_ids(c2))
W6.execute('WHO = "Korvash"')
check(W6.eval('WowCompanionSpellsDB.partyBy["Korvash-Realm"].v') == 1 and len(W6.eval('WowCompanionSpellsDB.partyBy["Korvash-Realm"].buffs')) == 0,
      "the record: v = 1, Smart Y's party list starts EMPTY")

# ================================================================ 10b. THE PARTY ROW (2.2)
row = W6.eval('WowCompanion.pad.rows.party')
check(row is not None and row.secure and row.n == 40 and row.onPress is True, "the party row: secure, 40 columns, opts.onPress", row and row.n)
cell = lambda col: row.cells[col]
U = ["player", "party1", "party2", "party3", "party4", "pet"]                  # m131: col 5 = the pet's pick cell
bad = []
for col in range(40):
    a = cell(col).a
    t, sp, un, cb = a['type'], a['spell'], a['unit'], a['clickbutton']
    if col <= 5: want = (None, None, None, None)                                            # m130: the pick cells (wrapped, no action at rest; m131 + 5 the pet's)
    elif col <= 9: want = (None, None, None, None)                                          # m129: the old touch cells retired
    elif col == 10: want = ("click", None, None, "WowCompanionPartyOn")
    elif col == 11: want = ("click", None, None, "WowCompanionPartyOff")
    elif col <= 16: want = ("focus", None, U[col - 12], None)
    elif col == 35: want = ("click", None, None, "WowCompanionPartyMenuDone")
    elif col == 36: want = ("macro", None, None, None)          # m91: Hub's /startattack cell (WC.atkCell)
    else: want = (None, None, None, None)
    got = (t, sp, un, cb._name if cb is not None else None)
    if got != want:
        bad.append((col, got, want))
check(not bad, "every party row cell (m130: PICK 0-4 = wrapped cells, m131: 5 the pet's, 6-9 spare, ON 10, OFF 11, FOCUS 12-16, MENU DONE 35)", bad[:3])
pk = lambda i: cell(i)
check(all(pk(i).a['wc-pu'] == U[i] and pk(i).a['wc-touch'] == "Lesser Healing Wave" and pk(i).refs['wc-pad'] is not None
          and len(pk(i).wraps['OnClick']) == 1 and len(pk(i).hooks['PostClick']) == 1 for i in range(6)),
      "m130: each pick CELL (m131: the pet's col 5 too): wrapped once (PARTYPICK) with one PostClick, its unit (wc-pu), the Touch spell, a ref to the pad")
check(W6.eval('rawget(_G, "WowCompanionPartyPick1")') is None, "m130: no WowCompanionPartyPick buttons (no click relay)")
W6.eval('WowCompanion.padMap.party.put')("touch", False)
check(all(pk(i).a['wc-touch'] is None for i in range(6)), "Touch = Target: no wc-touch on the pick cells")
W6.execute('local r = WowCompanion.pad.rows.party; r.built = false; WowCompanion.pad.buildSecure()')
check(all(len(pk(i).wraps['OnClick']) == 1 and len(pk(i).hooks['PostClick']) == 1 for i in range(6)),
      "m130: a rebuilt row never wraps a pick cell twice")
# m129 A PLATE'S TAP (party cols 0-4): off = Target; on: another member = the pick moves (nothing cast); the picked one = Touch
W6.eval('party')(W6.table_from([W6.table_from(m) for m in MEMBERS[:2]]))
W6.execute('fire("GROUP_ROSTER_UPDATE"); advance(1)')


def tap6(col):
    a0 = nact(W6)
    n0 = nsent(W6)
    W6.execute('local c = WowCompanion.pad.rows.party.cells[%d]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false); NOW = NOW + 0.3' % col)
    return acts(W6, a0), docs(W6, n0, 'partybox')


got, pb = tap6(2)
check(got == [("target", None, "party2")] and pb and pb[-1]['b']['k'] == 'target', "m129: Party mode OFF: a plate's tap = Target (party2), partybox k target",
      (got, pb and pb[-1]['b']['k']))
W6.execute('local c = WowCompanion.pad.rows.party.cells[10]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false); advance(0.3)')
got, pb = tap6(2)
check(got == [("target", None, "party2")] and W6.eval('PADA("wc-box")') == 3 and W6.eval('PADA("wc-u")') == "party2" and pb and pb[-1]['b']['k'] == 'pick'
      and pb[-1]['b']['box'] == 3 and pb[-1]['b']['u'] == 'party2',
      "m134: ON, a tap on party2's plate: the pick moves (box 3, wc-u party2) and TARGETS party2, nothing cast, k pick",
      (got, W6.eval('PADA("wc-box")'), pb and pb[-1]['b']))
got, pb = tap6(2)
check(got == [("target", None, "party2")] and W6.eval('PADA("wc-box")') == 3 and pb and pb[-1]['b']['k'] == 'touch',
      "m129: a tap on the PICKED plate: the Touch action (Target party2), partybox k touch", (got, pb and pb[-1]['b']['k']))
W6.eval('WowCompanion.padMap.party.put')("touch", 8004)
got, pb = tap6(2)
check(got == [("spell", "Lesser Healing Wave", "party2")], "m129: Touch = Lesser Healing Wave: the picked plate's tap casts it on party2", got)
W6.execute('COMBAT = true')
got, pb = tap6(0)
check(got == [("target", None, "player")] and W6.eval('PADA("wc-box")') == 1 and W6.eval('PADA("wc-u")') == "player" and W6.eval('#VIOL') == 0,
      "m134: in a fight a tap still picks and targets (you: box 1, secure only)", (got, W6.eval('PADA("wc-box")'), list(W6.eval('VIOL').values())[:2]))
got, pb = tap6(4)
check(got == [] and W6.eval('PADA("wc-box")') == 1 and pb and pb[-1]['b']['k'] == 'none', "m129: a plate with nobody there (party4): nothing, k none",
      (got, pb and pb[-1]['b']['k']))
got, pb = tap6(5)
check(got == [] and W6.eval('PADA("wc-box")') == 1 and pb and pb[-1]['b']['k'] == 'none', "m131: the pet's cell with no pet out: nothing, k none",
      (got, pb and pb[-1]['b']['k']))
# m131 THE PET'S CELL (col 5): the pet is entry 2, so party1 / party2 are 3 / 4
W6.execute('UNITS.pet = { name = "Wolf", alive = true }')
got, pb = tap6(5)
check(got == [("target", None, "pet")] and W6.eval('PADA("wc-box")') == 2 and W6.eval('PADA("wc-u")') == "pet" and pb and pb[-1]['b']['k'] == 'pick'
      and pb[-1]['b']['cnt'] == 4, "m134: in a fight, the pet's plate: the pick moves to it and targets it (box 2, wc-u pet, cnt 4)",
      (got, W6.eval('PADA("wc-box")'), pb and pb[-1]['b']))
got, pb = tap6(2)
check(got == [("target", None, "party2")] and W6.eval('PADA("wc-box")') == 4 and W6.eval('PADA("wc-u")') == "party2",
      "m131: party2's plate with the pet out: box 4 (targeted, m134)",
      (got, W6.eval('PADA("wc-box")')))
got, pb = tap6(5)
got, pb = tap6(5)
check(got == [("spell", "Lesser Healing Wave", "pet")] and pb and pb[-1]['b']['k'] == 'touch', "m131: the picked pet's plate: the Touch action (the Touch spell on \"pet\")",
      (got, pb and pb[-1]['b']['k']))
W6.execute('UNITS.pet = nil; local p = rawget(_G, "WowCompanionPartyPad"); p.a["wc-box"] = 1; p.a["wc-u"] = "player"')
W6.execute('COMBAT = false')


# m130 (review 2026-11-11): ONE DECISION A PRESS, whatever edges arrive
def edges(col, seq):
    a0 = nact(W6)
    n0 = nsent(W6)
    W6.execute(' '.join('doClick(WowCompanion.pad.rows.party.cells[%d], "LeftButton", %s);' % (col, 'true' if d else 'false') for d in seq)
               + ' NOW = NOW + 0.3')
    return acts(W6, a0), docs(W6, n0, 'partybox')


W6.execute('local P = rawget(_G, "WowCompanionPartyPad"); P.a["wc-box"] = 1; P.a["wc-u"] = "player"')
for ond in (True, False):
    W6.execute('ACT_ON_DOWN = %s' % ('true' if ond else 'false'))
    W6.execute('local P = rawget(_G, "WowCompanionPartyPad"); P.a["wc-box"] = 1; P.a["wc-u"] = "player"')
    got, pb = edges(2, [True, False])
    check(got == [("target", None, "party2")] and W6.eval('PADA("wc-box")') == 3 and len(pb) == 1 and pb[0]['b']['k'] == 'pick',
          "m130: down + up on an unpicked plate (action on %s): ONE pick (m134: one Target), nothing cast, one partybox" % ("down" if ond else "up"),
          (got, W6.eval('PADA("wc-box")'), [d['b']['k'] for d in pb]))
    # pad5: one tap can arrive as TWO downs and one up; a real mouse tap acts on the up (TapPad's measure), so the two-down case is
    # the action-on-up one; with the action on the down a cell fires on each down whatever the snippet says (as the old cells did)
    got, pb = edges(2, [True, True, False] if not ond else [True, False])
    check(got == [("spell", "Lesser Healing Wave", "party2")] and len(pb) == 1 and pb[0]['b']['k'] == 'touch',
          "m130: %s on the picked plate: ONE Touch (Lesser Healing Wave), one partybox" % ("two downs + one up" if not ond else "down + up"),
          (got, [d['b']['k'] for d in pb]))
    got, pb = edges(0, [False])
    # m134: the pick's Target fires where the action edge is the up (the cell acts on that edge only, as the Touch below)
    check(got == ([] if ond else [("target", None, "player")]) and W6.eval('PADA("wc-box")') == 1 and len(pb) == 1 and pb[0]['b']['k'] == 'pick',
          "m130: an up alone (no down heard): decided on the up, the pick moves (you)", (got, W6.eval('PADA("wc-box")')))
    got, pb = edges(0, [False])
    check(got == ([] if ond else [("spell", "Lesser Healing Wave", "player")]) and len(pb) == 1 and pb[0]['b']['k'] == 'touch',
          "m130: an up alone on the picked plate: Touch (fires only where the action edge is the up)", (got, [d['b']['k'] for d in pb]))
# a lost up: the next press re-uses that decision once, then the cell is clean again
W6.execute('ACT_ON_DOWN = true; local P = rawget(_G, "WowCompanionPartyPad"); P.a["wc-box"] = 1; P.a["wc-u"] = "player"')
got, pb = edges(2, [True])
check(W6.eval('PADA("wc-box")') == 3 and W6.eval('WowCompanion.pad.rows.party.cells[2].a["wc-pdec"]') == "pick", "m130: a down whose up is lost: the pick, kept")
got, pb = edges(2, [True, False])
check(got == [("target", None, "party2")] and pb == [] and W6.eval('WowCompanion.pad.rows.party.cells[2].a["wc-pdec"]') is None,
      "m130: the next press re-uses it (no Touch, no box; m134: the pick's Target again) and its up clears it", (got, pb))
got, pb = edges(2, [True, False])
check(got == [("spell", "Lesser Healing Wave", "party2")] and len(pb) == 1 and pb[0]['b']['k'] == 'touch', "m130: and the press after that is the Touch again",
      (got, [d['b']['k'] for d in pb]))
# Party mode off: Target by a plate tap on either action edge
W6.execute('local c = WowCompanion.pad.rows.party.cells[11]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false); advance(0.3)')
for ond in (True, False):
    W6.execute('ACT_ON_DOWN = %s' % ('true' if ond else 'false'))
    got, pb = edges(1, [True, False])
    check(got == [("target", None, "party1")] and len(pb) == 1 and pb[0]['b']['k'] == 'target',
          "m130: Party mode OFF, action on %s: a plate's tap = ONE Target (the cell's own action)" % ("down" if ond else "up"),
          (got, [d['b']['k'] for d in pb]))
W6.execute('ACT_ON_DOWN = true')
W6.eval('WowCompanion.padMap.party.put')("touch", False)
W6.eval('party')(W6.table_from([]))
W6.execute('fire("GROUP_ROSTER_UPDATE"); advance(1)')
check(W6.eval('#SNIPERR') == 0 and W6.eval('#SPY') == 0, "the pick snippet: no restricted-environment error, no spied call", W6.eval('SNIPERR[1]'))
d = W6.eval('WowCompanion.pad.describe()')
check(d['rows']['party'] is not None and int(d['sizes']['party']) == 40, "describe() publishes the party row (40 columns)", d['rows']['party'])
# a tap on cell 10 / 11: Party mode on / off by the secure click
W6.execute('local c = WowCompanion.pad.rows.party.cells[10]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false); advance(0.3)')
check(W6.eval('PADA("wc-party")') is True, "a tap on party col 10: Party mode ON", W6.eval('PADA("wc-party")'))
W6.execute('local c = WowCompanion.pad.rows.party.cells[11]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false); advance(0.3)')
check(W6.eval('PADA("wc-party")') is None, "a tap on party col 11: Party mode OFF")
n0 = nsent(W6)
W6.execute('local c = WowCompanion.pad.rows.party.cells[37]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false)')
ack = docs(W6, n0, 'partyack')
check(len(ack) == 1 and ack[0]['ok'] is False and ack[0]['why'] == 'unknown column', "a spare column (37) is refused: unknown column", ack)

# ================================================================ m150 SMART RES (the user, 2026-11-13: "There should be an enable smart res
# toggle setting for party mode to enable pressing dead team members on touch if they are dead and I am playing a class with a res.
# Similarly a revive pet would be neat."; the pick "Tap the dead member")
def sres_world(cls, book, on=True, extra=''):
    L = world(pre=extra + 'CLASS = "%s"; SPELLNAME[2008] = "Ancestral Spirit"; SPELLNAME[2006] = "Resurrection"; SPELLNAME[20484] = "Rebirth"; '
                  'SPELLNAME[982] = "Revive Pet"; SPELLNAME[883] = "Call Pet"; BOOK = { %s }; WowCompanion.smartRes = %s'
                  % (cls, ", ".join(str(b) for b in book), "true" if on else "nil"))
    L.eval('party')(L.table_from([L.table_from(m) for m in [["Brannoc", "WARRIOR", False, "TANK"], ["Iselle", "MAGE"]]]))
    L.execute('fire("GROUP_ROSTER_UPDATE"); advance(1)')
    return L


def sres_tap(L, col):
    a0 = nact(L)
    n0 = nsent(L)
    L.execute('local c = WowCompanion.pad.rows.party.cells[%d]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false); NOW = NOW + 0.3' % col)
    pb = docs(L, n0, 'partybox')
    return acts(L, a0), (pb[-1]['b']['k'] if pb else None)


def rcell(L, col, k):
    return L.eval('WowCompanion.pad.rows.party.cells[%d].a["%s"]' % (col, k))


SR = sres_world("SHAMAN", [403, 331, 2008])
check([rcell(SR, i, "wc-res") for i in range(6)] == [None, "Ancestral Spirit", "Ancestral Spirit", "Ancestral Spirit", "Ancestral Spirit", None]
      and all(rcell(SR, i, "wc-resc") is None for i in range(6)),
      "m150: a Shaman with Smart Res on: party1..4's cells armed with Ancestral Spirit (not you, not the pet), none castable in a fight",
      [rcell(SR, i, "wc-res") for i in range(6)])
got, k = sres_tap(SR, 1)
check(got == [("spell", "Ancestral Spirit", "party1")] and k == "res" and SR.eval('PADA("wc-box")') == 1,
      "m150: Party mode OFF, a tap on dead party1: Ancestral Spirit on party1 (no Target, the pick stays), k res", (got, k))
got, k = sres_tap(SR, 2)
check(got == [("target", None, "party2")] and k == "target", "m150: a live member's tap is unchanged (Target)", (got, k))
SR.execute('local c = WowCompanion.pad.rows.party.cells[10]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false); advance(0.3)')
got, k = sres_tap(SR, 1)
check(got == [("spell", "Ancestral Spirit", "party1")] and k == "res" and SR.eval('PADA("wc-box")') == 1,
      "m150: Party mode ON, a tap on dead party1 (not the pick): the res, the pick does not move", (got, k, SR.eval('PADA("wc-box")')))
SR.execute('COMBAT = true')
got, k = sres_tap(SR, 1)
check(got == [] and k == "nores" and SR.eval('#VIOL') == 0, "m150: in a fight a Shaman's tap on a dead member: nothing cast, k nores", (got, k))
got, k = sres_tap(SR, 2)
check(got == [("target", None, "party2")] and k == "pick", "m150: in a fight a live member's tap still picks", (got, k))
SR.execute('COMBAT = false')
pd = docs(SR, 0, 'party')[-1]['p']
check(pd.get('res') is True and 'pd' not in pd, "m150: the party document says res (armed), no dead pet entry", sorted(pd))
SR.execute('WowCompanion.smartRes = false; WowCompanion.smartResSet(false); advance(1)')
check(all(rcell(SR, i, "wc-res") is None for i in range(6)), "m150: Smart Res off: every cell disarmed (out of combat)")
got, k = sres_tap(SR, 1)
check(got == [("target", None, "party1")], "m150: Smart Res off: a dead member's tap is today's (Target)", (got, k))
check('res' not in docs(SR, 0, 'party')[-1]['p'], "m150: ... and the document drops res")
SR.execute('COMBAT = true; WowCompanion.smartRes = true; WowCompanion.smartResSet(true)')
check(rcell(SR, 1, "wc-res") is None and SR.eval('#VIOL') == 0, "m150: switched on IN a fight: no secure write in the lockdown")
SR.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
check(rcell(SR, 1, "wc-res") == "Ancestral Spirit", "m150: ... armed at the fight's end", rcell(SR, 1, "wc-res"))

# a Shaman who has not learned the res: nothing armed
SN = sres_world("SHAMAN", [403, 331])
check(all(rcell(SN, i, "wc-res") is None for i in range(6)) and sres_tap(SN, 1)[0] == [("target", None, "party1")],
      "m150: a Shaman without Ancestral Spirit learned: no res armed, a tap targets")
# classes without a res: nothing changes
SW_ = sres_world("WARRIOR", [6673])
check(all(rcell(SW_, i, "wc-res") is None for i in range(6)), "m150: a Warrior: nothing armed")

# a Druid: Rebirth, castable in a fight too
SD = sres_world("DRUID", [20484])
check(rcell(SD, 1, "wc-res") == "Rebirth" and rcell(SD, 1, "wc-resc") is True, "m150: a Druid: Rebirth, castable in a fight (wc-resc)")
SD.execute('COMBAT = true')
got, k = sres_tap(SD, 1)
check(got == [("spell", "Rebirth", "party1")] and k == "res" and SD.eval('#VIOL') == 0, "m150: a Druid in a fight: Rebirth on dead party1", (got, k))
SD.execute('COMBAT = false')
# a Priest: Resurrection
SP = sres_world("PRIEST", [2006])
check(sres_tap(SP, 1)[0] == [("spell", "Resurrection", "party1")], "m150: a Priest: Resurrection")

# a Hunter: Revive Pet on the pet's cell (dead and gone), the dead pet entry in the document
SH = sres_world("HUNTER", [982, 883])
check(rcell(SH, 5, "wc-res") == "Revive Pet" and rcell(SH, 5, "wc-resc") is True and rcell(SH, 1, "wc-res") is None,
      "m150: a Hunter: the pet's cell armed with Revive Pet (any time), no party res")
SH.execute('UNITS.pet = { name = "Wolf", alive = true }; fire("UNIT_PET", "player"); advance(1)')
got, k = sres_tap(SH, 5)
check(got == [("target", None, "pet")], "m150: a live pet's tap is today's (Target)", (got, k))
SH.execute('UNITS.pet.alive = false; fire("UNIT_HEALTH", "pet"); advance(1)')
p = docs(SH, 0, 'party')[-1]['p']
check([m['dead'] for m in p['m'] if m['u'] == 'pet'] == [True] and 'pd' not in p, "m150: the pet dead but still out: its own entry, dead", p.get('pd'))
got, k = sres_tap(SH, 5)
check(got == [("spell", "Revive Pet", "pet")] and k == "res", "m150: a tap on the dead pet: Revive Pet", (got, k))
SH.execute('UNITS.pet = nil; fire("UNIT_PET", "player"); advance(1)')
p = docs(SH, 0, 'party')[-1]['p']
check(p.get('pd') == {"n": "Wolf", "ic": None} or (p.get('pd') or {}).get('n') == "Wolf", "m150: the dead pet gone: the document's pd (its last name)", p.get('pd'))
check(all(m['u'] != 'pet' for m in p['m']), "m150: ... and no pet member (the pick walk unchanged)")
SH.execute('COMBAT = true')
got, k = sres_tap(SH, 5)
check(got == [("spell", "Revive Pet", None)] and k == "res" and SH.eval('#VIOL') == 0, "m150: a tap on the gone dead pet's cell, in a fight too: Revive Pet (no unit)", (got, k))
SH.execute('COMBAT = false; UNITS.pet = { name = "Wolf", alive = true }; fire("UNIT_PET", "player"); advance(1.5)')
check('pd' not in docs(SH, 0, 'party')[-1]['p'], "m150: the pet back alive: no dead pet entry")
SH.execute('UNITS.pet = nil; fire("UNIT_PET", "player"); advance(1)')
check('pd' not in docs(SH, 0, 'party')[-1]['p'], "m150: a pet dismissed alive: no dead pet entry")
# m151 (review): the dead pet kept per character across a /reload / login; a pet that leaves in a fight counts as dead
SK = sres_world("HUNTER", [982, 883])
SK.execute('UNITS.pet = { name = "Wolf", alive = true }; fire("UNIT_PET", "player"); advance(1)')
SK.execute('UNITS.pet.alive = false; fire("UNIT_HEALTH", "pet"); advance(1)')
check(SK.eval('WowCompanionSpellsDB.petDeadBy["Korvash-Realm"].n') == "Wolf", "m151: the pet seen dead: kept per character (petDeadBy[name-realm])",
      SK.eval('WowCompanionSpellsDB.petDeadBy["Korvash-Realm"]'))
SK.execute('UNITS.pet = { name = "Wolf", alive = true }; fire("UNIT_PET", "player"); advance(1.5)')
check(SK.eval('WowCompanionSpellsDB.petDeadBy["Korvash-Realm"]') is None, "m151: a live pet seen: the kept record cleared")
SL = sres_world("HUNTER", [982, 883], extra='WowCompanionSpellsDB = { petDeadBy = { ["Korvash-Realm"] = { n = "Wolf", ic = 132 } } }; ')
SL.execute('advance(1.5)')
p = docs(SL, 0, 'party')[-1]['p']
check((p.get('pd') or {}).get('n') == "Wolf" and (p.get('pd') or {}).get('ic') == 132, "m151: after a /reload / login with the pet dead and gone: pd from the kept record", p.get('pd'))
got, k = sres_tap(SL, 5)
check(got == [("spell", "Revive Pet", None)] and k == "res", "m151: ... and its cell casts Revive Pet", (got, k))
SO = sres_world("HUNTER", [982, 883], extra='WowCompanionSpellsDB = { petDeadBy = { ["Alt-Realm"] = { n = "Cat" } } }; ')
SO.execute('advance(1.5)')
check('pd' not in docs(SO, 0, 'party')[-1]['p'], "m151: another character's dead pet never shows")
SF = sres_world("HUNTER", [982, 883])
SF.execute('UNITS.pet = { name = "Boar", alive = true }; fire("UNIT_PET", "player"); advance(1)')
SF.execute('COMBAT = true; UNITS.pet = nil; fire("UNIT_PET", "player"); advance(1)')
p = docs(SF, 0, 'party')[-1]['p']
check((p.get('pd') or {}).get('n') == "Boar" and SF.eval('WowCompanionSpellsDB.petDeadBy["Korvash-Realm"].n') == "Boar",
      "m151: a live pet's unit gone IN a fight (never seen dead): the dead pet entry, kept", p.get('pd'))
SF.execute('COMBAT = false; UNITS.pet = { name = "Boar", alive = true }; fire("UNIT_PET", "player"); advance(1.5)')
check('pd' not in docs(SF, 0, 'party')[-1]['p'], "m151: ... the pet called back: gone")
SF.execute('COMBAT = true; UNITS.player.alive = false; UNITS.pet = nil; fire("UNIT_PET", "player"); advance(1)')
check('pd' not in docs(SF, 0, 'party')[-1]['p'], "m151: the pet gone in a fight because YOU died: no dead pet entry")
SF.execute('COMBAT = false; UNITS.player.alive = true')
SG = sres_world("SHAMAN", [403, 331, 2008])
SG.execute('UNITS.pet = { name = "Wolf", alive = true }; fire("UNIT_PET", "player"); advance(1); COMBAT = true; UNITS.pet = nil; fire("UNIT_PET", "player"); advance(1)')
check('pd' not in docs(SG, 0, 'party')[-1]['p'] and SG.eval('(WowCompanionSpellsDB.petDeadBy or {})["Korvash-Realm"]') is None,
      "m151: no Revive Pet (a Shaman's wolf gone in a fight): no dead pet entry")
SG.execute('COMBAT = false')
for L in (SR, SD, SP, SH, SK, SL, SO, SF, SG):
    check(L.eval('#SNIPERR') == 0 and L.eval('#SPY') == 0, "m150: no snippet error, no spied call", L.eval('SNIPERR[1]'))

# ================================================================ 5. THE COMBAT LOCKDOWN (secure work from insecure code in a fight errors)
W5 = world()
W5.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true')
n0 = nsent(W5)
r = W5.eval('WowCompanion.padMap.party.put')("a", 331)
ack = docs(W5, n0, 'partyack')
check(r[0] is False and ack and ack[0]['ok'] is False and ack[0]['why'] == 'combat' and ack[0]['op'] == 'slot', "a setting in a fight: partyack ok false, why combat", ack)
for slot, op in (("yc", "yc"), ("touch", "touch")):
    n0 = nsent(W5)
    W5.eval('WowCompanion.padMap.party.put')(slot, 331)
    ack = docs(W5, n0, 'partyack')
    check(ack and ack[0]['op'] == op and ack[0]['why'] == 'combat', "... and for " + op, ack)
check(W5.eval('WowCompanionSpellsDB.partyBy["Korvash-Realm"].yc') is None, "nothing stored in the fight")
W5.execute('BOOK = { 403, 331, 324, 8004 }; fire("SPELLS_CHANGED"); advance(1)')
check(W5.eval('PADA("wc-ps-x")') is None and W5.eval('#VIOL') == 0, "a spell learned in the fight: nothing applied yet, no lockdown breach",
      list(W5.eval('VIOL').values())[:2])
W5.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
check(W5.eval('PADA("wc-ps-x")') == "Lesser Healing Wave", "... applied at PLAYER_REGEN_ENABLED (x = Lesser Healing Wave)", W5.eval('PADA("wc-ps-x")'))
check(W5.eval('#VIOL') == 0 and W5.eval('#SPY') == 0, "no lockdown breach, no spied call", (list(W5.eval('VIOL').values())[:2], list(W5.eval('SPY').values())[:2]))

# ================================================================ 8. NO HEALTH FOR THE BOTTOM (m129, party_design.md A.5)
# the guessed-health chain is gone: no estimate block, no drawn-fill read, no hit / cast counts, no party UNIT_HEALTH / UNIT_COMBAT,
# no combat log; Smart Y's own model (OV.hmF) stays, and so does the Advanced Combat Logging give-back
PMSRC = SRC["padmap"]
check('PARTY-EST-BEGIN' not in PMSRC and 'function PM.readHp' not in PMSRC and 'GetStatusBarTexture' not in PMSRC and 'PM.hits' not in PMSRC
      and 'OV.peHit' not in PMSRC, "m129: the estimate, the drawn-fill read (m86) and the hit counts are gone", "")
check('LoggingCombat' not in PMSRC and '"advancedCombatLogging", "1"' not in PMSRC and '"advancedCombatLogging", "0"' in PMSRC
      and 'aclReset1' in PMSRC and 'OV.hmF' in PMSRC, "m129: the combat log never started; the give-back and Smart Y's OV.hmF kept", "")
ev = PMSRC[PMSRC.index('    PM.ev = CreateFrame("Frame")'):PMSRC.index('    PM.ev:SetScript("OnEvent"')]
# m150: the one UNIT_HEALTH left is the PET's own unit event for its death (Smart Res's Revive Pet: dead / alive only, no amount read)
ev_list = ev[:ev.index('pcall(function() PM.ev:RegisterUnitEvent("UNIT_HEALTH", "pet") end)')]
check('"UNIT_COMBAT"' not in ev and '"UNIT_HEALTH"' not in ev_list and '"UNIT_SPELLCAST_SUCCEEDED"' not in ev
      and ev.count('UNIT_HEALTH') == 1 and 'UnitHealth(' not in PMSRC[PMSRC.index('function partyMode()'):PMSRC.index('\npartyMode()\n')],
      "m129: Party mode registers no UNIT_HEALTH / UNIT_COMBAT / cast events (m150: only the pet's own death watch, no amount read)", "")
# the give-back: ours (aclOurs), or once for 1.0.17 / 1.0.18 (aclReset1 unset); never a write when it was the player's own
for db, adv, want in (('{ aclOurs = true, aclReset1 = true }', "1", "0"), ('{}', "1", "0"), ('{ aclReset1 = true }', "1", "1"), ('{ aclOurs = true }', "0", "0")):
    GB = boot(["safety", "tappad", "book", "padmap"], pre='WowCompanionSpellsDB = %s; CVS = { advancedCombatLogging = "%s" }; '
              'C_CVar = { GetCVar = function(k) return CVS[k] end, SetCVar = function(k, v) CVS[k] = v end }' % (db, adv))
    GB.execute('fire("PLAYER_ENTERING_WORLD"); runTimers(NOW + 3)')
    check(GB.eval('CVS.advancedCombatLogging') == want and GB.eval('WowCompanionSpellsDB.aclReset1') is True and GB.eval('WowCompanionSpellsDB.aclOurs') is None,
          "m129: the give-back with %s, the option %s: %s afterwards" % (db, adv, want), GB.eval('CVS.advancedCombatLogging'))

# ================================================================ 7. THE DOCUMENTS (key for key with PARTY_BUILD.md section 1)
W7 = world()
W7.eval('party')(W7.table_from([W7.table_from(["Brannoc", "WARRIOR", True, "TANK", "RAGE"])]))
W7.execute('fire("GROUP_ROSTER_UPDATE")')
P_KEYS = {"on", "aside", "box", "fight", "grp", "sel", "m", "res", "pd"}     # m150: Smart Res armed, a Hunter's dead pet
M_KEYS = {"u", "i", "n", "c", "col", "role", "dead", "off", "pt", "lv", "rng", "ic"}   # m129: plain facts only (no hp / full / hs / g / hit counts); m134: the pet's ic
d = docs(W7, 0, 'party')[-1]
check(set(d) == {"type", "v", "rs", "p"} and d['v'] == 'm154' and set(d['p']) <= P_KEYS and {"on", "fight", "grp", "sel", "m"} <= set(d['p']),
      "party: type v rs p{on aside? box? fight grp sel m}", sorted(d['p']))
check('box' not in d['p'] and d['p']['on'] is False and d['p']['grp'] is True and d['p']['sel'] == 'bound',
      "Party mode off: no box; grp true; sel bound (Select toggles)", (d['p']['on'], d['p'].get('box'), d['p']['sel']))
m = d['p']['m']
check(len(m) == 2 and [x['u'] for x in m] == ['player', 'party1'] and [x['i'] for x in m] == [0, 1], "m[]: you first, then party1 (i = the tap offset)",
      [(x['u'], x['i']) for x in m])
check(all(set(x) <= M_KEYS for x in m) and m[1]['n'] == 'Brannoc' and m[1]['c'] == 'WARRIOR' and m[1]['col'] == 'ffc69b6d'
      and m[1]['role'] == 'TANK' and m[1]['pt'] == 'RAGE' and m[1]['lv'] == 20
      and m[1]['dead'] is False and m[1]['off'] is False, "a member: n c col role dead off pt lv (m129: no health, no mana)", m[1])
check(m[0]['n'] == 'Korvash' and m[0]['c'] == 'SHAMAN' and m[0]['pt'] == 'MANA', "your own cell", m[0])
check(all(not isinstance(v, (dict, list)) or k == 'm' for k, v in d['p'].items()), "no secret value can ride: plain fields only")
rs0 = d['rs']
n0, p0 = nsent(W7), int(W7.eval('PUSHES'))
W7.eval('party')(W7.table_from([W7.table_from(["Brannoc", "WARRIOR", True, "TANK", "RAGE"]), W7.table_from(["Iselle", "MAGE", True, "DAMAGER", "MANA"])]))
W7.execute('fire("GROUP_ROSTER_UPDATE")')
d = docs(W7, n0, 'party')
check(len(d) == 1 and d[0]['rs'] == rs0 + 1 and len(d[0]['p']['m']) == 3 and int(W7.eval('PUSHES')) > p0,
      "a roster change: a party document AT ONCE (no timer), rs + 1, flushed", (len(d), d and d[0]['rs'], rs0))
# m129 range from ONE place (UnitInRange, checked plainly true): the document's rng and WC.padMap.party.rng answer alike; you never dim
W7.execute('INR = { party1 = { false, true }, party2 = { true, false } }; '
           'function UnitInRange(u) local r = INR[u]; if not r then return nil end; return r[1], r[2] end; tick(1); advance(0.6)')
d = docs(W7, 0, 'party')[-1]
rr = W7.eval('WowCompanion.padMap.party.rng')
check(d['p']['m'][1].get('rng') is False and 'rng' not in d['p']['m'][2] and 'rng' not in d['p']['m'][0]
      and rr("party1") is False and rr("party2") is None and rr("player") is None,
      "m129: party1 out of range (checked): rng false on both screens; party2 not checked: no answer; you: none", [x.get('rng') for x in d['p']['m']])
W7.execute('INR = { party1 = { SECRETV, true } }; tick(1); advance(0.6)')
check('rng' not in docs(W7, 0, 'party')[-1]['p']['m'][1] and rr("party1") is None, "m129: a secret answer: no dim, no error", "")
W7.execute('INR = {}; UNITS.party2.alive = false; tick(1); advance(0.6)')
d = docs(W7, 0, 'party')[-1]
check(d['p']['m'][2]['dead'] is True, "a dead member: dead", d['p']['m'][2])
W7.execute('UNITS.party2.alive = true; tick(1); advance(0.6)')
# m131 THE PET (the user, 2026-11-11: "WHEN NO PET MAKE THE PLAYER FULL SIZED"): an entry right after you while one plainly exists; a pet
# coming out is a roster change (UNIT_PET: rs + 1, at once); its member: i 5 (the pet's pick cell), no class / role, never offline or dim
n0 = nsent(W7)
W7.execute('UNITS.pet = { name = "Wolf", alive = true }; fire("UNIT_PET", "player")')
d = docs(W7, n0, 'party')
check(len(d) == 1 and d[0]['rs'] == rs0 + 2 and [x['u'] for x in d[0]['p']['m']] == ['player', 'pet', 'party1', 'party2']
      and [x['i'] for x in d[0]['p']['m']] == [0, 5, 1, 2],
      "m131: the pet out: a party document at once (rs + 1), m = you, pet, party1, party2 (i 0 5 1 2)", d and [(x['u'], x['i']) for x in d[0]['p']['m']])
pm = d[0]['p']['m'][1] if d else {}
check(set(pm) <= M_KEYS and pm.get('n') == 'Wolf' and 'c' not in pm and 'col' not in pm and pm.get('role') == 'NONE' and pm.get('off') is False
      and 'rng' not in pm, "m131: the pet's member: its name, no class colour, role NONE, never offline, no range", pm)
check([x for x in W7.eval('WowCompanion.padMap.party.units()').values()] == ['player', 'pet', 'party1', 'party2']
      and W7.eval('WowCompanion.padMap.party.rng("pet")') is None and W7.eval('WowCompanion.padMap.party.nav(4, 2, "down")') == 3,
      "m131: the exports: units() in pick order (you, pet, party1..), rng(pet) none, nav = OV.partyNav", "")
W7.execute('UNITS.pet = { name = "Wolf", alive = true }')
W7.execute('UnitExists = function(u) if u == "pet" then return SECRETV end; return UNITS[u] ~= nil end')
check(W7.eval('WowCompanion.padMap.party.units()')[2] == 'pet', "m131: UnitExists secret: the pet still found by a plain read (its name / HasPetUI)", "")
W7.execute('UNITS.pet = nil; UnitExists = function(u) return UNITS[u] ~= nil end; fire("UNIT_PET", "player")')
check([x['u'] for x in docs(W7, 0, 'party')[-1]['p']['m']] == ['player', 'party1', 'party2'], "m131: the pet gone: no entry for it", "")
check(W7.eval('WowCompanion.padMap.party.selWant()') == "bound" and W7.eval('WowCompanion.padMap.party.grouped()') is True,
      "m129: the exports Hub reads: selWant bound, grouped", (W7.eval('WowCompanion.padMap.party.selWant()'),))
# Party mode on: box present (1-based into m)
flip(W7, "WowCompanionPartyOn")
d = docs(W7, 0, 'party')[-1]
check(d['p']['on'] is True and d['p']['box'] == 1, "Party mode on: box 1 (you, the first time)", (d['p']['on'], d['p'].get('box')))
W7.execute('hit(4); advance(0.6)')
pb = docs(W7, 0, 'partybox')[-1]
B_KEYS = {"n", "on", "box", "cnt", "u", "k", "key", "ly", "sp", "fight", "pm"}
check(set(pb) == {"type", "v", "b"} and set(pb['b']) <= B_KEYS and pb['b']['k'] == 'move' and pb['b']['box'] == 2 and pb['b']['u'] == 'party1'
      and pb['b']['cnt'] == 3 and pb['b']['key'] == 'PADDDOWN' and pb['b']['ly'] == '' and pb['b']['on'] is True and pb['b']['pm'] is False,
      "partybox: n on box cnt u k key ly sp? fight pm", pb['b'])
W7.execute('trig(true, false); hit(8); trig(false, false)')
pb2 = docs(W7, 0, 'partybox')[-1]['b']
check(pb2['n'] == pb['b']['n'] + 1 and pb2['ly'] == 'zl' and pb2['k'] == 'none' and 'sp' not in pb2,
      "the press counter goes up by one per press; ZL+A with Cure Poison not learned: ly zl, k none, no sp", pb2)
W7.execute('hit(8)')
pb3 = docs(W7, 0, 'partybox')[-1]['b']
check(pb3['k'] == 'cast' and pb3['sp'] == 'Healing Wave' and pb3['u'] == 'party1' and pb3['ly'] == '', "A: k cast, sp Healing Wave, u party1", pb3)
d = docs(W7, 0, 'party')[-1]
check(d['p']['box'] == 2, "the party document follows the box (2)", d['p'].get('box'))
# partycfg: key for key
c = docs(W7, 0, 'partycfg')[-1]
C_KEYS = {"slots", "yc", "touch", "spells", "sy", "pad"}
check(set(c) == {"type", "v", "c"} and set(c['c']) <= C_KEYS and {"slots", "spells", "sy", "pad"} <= set(c['c']), "partycfg: c{slots yc? touch? spells sy pad}",
      sorted(c['c']))
check(c['c']['pad'] == {"row": "party", "pick": 0, "on": 10, "off": 11, "focus": 12, "menu": 17, "resend": 22, "pbsel": 23,
                        "pbops": {"remove": 31, "up": 32, "down": 33, "add": 34}, "mdone": 35,
                        "ptab": {"row": "padtab", "tab": 26, "slot0": 27, "none": 37}}, "partycfg.pad = the contract's column map", c['c']['pad'])
check(set(c['c']['sy']) <= {"on", "list", "known", "pick"} and c['c']['sy']['on'] is False and c['c']['sy']['list'] == [],
      "sy: on (Smart Y off), list (EMPTY at first), known, pick", c['c']['sy'])
check(all(set(v) <= {"id", "n", "i", "k", "d", "res"} for v in c['c']['slots'].values()) and c['c']['slots']['a']['n'] == 'Healing Wave'
      and isinstance(c['c']['slots']['a']['i'], int), "slots.<s>: id n i k d res?", c['c']['slots']['a'])
check(all(set(v) == {"id", "n", "i"} for v in c['c']['spells']) and [v['id'] for v in c['c']['spells']] == [331],
      "spells (the chooser): Healing Wave only (Lightning Bolt harmful, Lightning Shield self-only)", c['c']['spells'])
# the heartbeat ride: partycfg + party on heartbeat / login / zone / manual, no push of their own
for reason in ("heartbeat", "login", "zone", "manual", "harvest"):
    n0, p0 = nsent(W7), int(W7.eval('PUSHES'))
    W7.eval('WowCompanion.snapshot')(reason)
    t = [x['type'] for x in docs(W7, n0) if x.get('type') in ('party', 'partycfg')]
    want = ['partycfg', 'party'] if reason != "harvest" else []
    check(t == want and int(W7.eval('PUSHES')) == p0, "WC.snapshot(%s): %s, riding its push" % (reason, want or "nothing"), (t, int(W7.eval('PUSHES')) - p0))
# RESEND: party col 22
n0, p0 = nsent(W7), int(W7.eval('PUSHES'))
W7.execute('local c = WowCompanion.pad.rows.party.cells[22]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false)')
t = [x['type'] for x in docs(W7, n0)]
st = [x for x in docs(W7, n0, 'partybox')]
ack = docs(W7, n0, 'partyack')
check(t[:4] == ['partycfg', 'party', 'partybox', 'partyack'] and st[0]['b']['k'] == 'state' and ack[0]['op'] == 'resend' and ack[0]['ok'] is True
      and int(W7.eval('PUSHES')) > p0, "RESEND (col 22): partycfg + party + partybox{k state} + partyack resend, flushed", t)
A_KEYS = {"type", "v", "op", "ok", "why", "slot", "id"}
check(set(ack[0]) <= A_KEYS, "partyack: type v op ok why? slot? id?", ack[0])
check(W7.eval('#SPY') == 0 and W7.eval('#VIOL') == 0 and W7.eval('#SNIPERR') == 0, "no spied call, no lockdown breach, no snippet error")

# ================================================================ 9. SMART Y'S PARTY BUFFS: picks per unit, PB select / ops / add
W9 = world(pre='BOOK = { 403, 331, 324, 131, 546 }')
W9.eval('party')(W9.table_from([W9.table_from(["Brannoc", "WARRIOR"]), W9.table_from(["Iselle", "MAGE"])]))
W9.execute('fire("GROUP_ROSTER_UPDATE"); advance(1)')


def tapcell(L, col):
    L.execute('local c = WowCompanion.pad.rows.party.cells[%d]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false); NOW = NOW + 0.3' % col)


def picks(L):
    return {u: L.eval('PADA("wc-pb-%s")' % u) for u in ("player", "party1", "party2")}


def buffs(L):
    t = L.eval('WowCompanionSpellsDB.partyBy["Korvash-Realm"].buffs')
    return [int(t[i]) for i in range(1, len(t) + 1)]


# the list by the app's taps: col 34 (a fresh add pick) then the spell (Book's two taps -> WC.smartYSpell)
for sid in (131, 546):
    tapcell(W9, 34)
    check(W9.eval('WowCompanion.smartYWants()') is True, "col 34: a fresh add pick (Book's next spell goes to the party list)")
    n0 = nsent(W9)
    W9.eval('WowCompanion.smartYSpell')(sid)
    ack = docs(W9, n0, 'partyack')
    check(ack and ack[0]['op'] == 'pb' and ack[0]['ok'] is True and ack[0]['id'] == sid, "add %d: partyack pb ok" % sid, ack)
check(buffs(W9) == [131, 546], "the party list: Water Breathing, Water Walking (priority order)", buffs(W9))
for sid, why in ((131, "already listed"), (1243, "not a party buff this character knows"), (331, "not a party buff this character knows")):
    tapcell(W9, 34)
    n0 = nsent(W9)
    W9.eval('WowCompanion.smartYSpell')(sid)
    ack = docs(W9, n0, 'partyack')
    check(ack and ack[0]['ok'] is False and ack[0]['why'] == why, "add %d refused: %s" % (sid, why), ack)
tapcell(W9, 34)
W9.execute('NOW = NOW + 2.5')
check(W9.eval('WowCompanion.smartYWants()') is False, "an add pick older than 2 s is stale (the spell is not taken)")
# Smart Y off: nothing picked
check(picks(W9) == {"player": None, "party1": None, "party2": None}, "Smart Y OFF: no party pick at all", picks(W9))
W9.execute('WowCompanionSpellsDB.smartOn = { ["Korvash-Realm"] = true }')
W9.execute('AURAS = { player = { "Water Breathing", "Water Walking" }, party2 = { "Water Breathing" } }')
n0 = nsent(W9)
W9.execute('fire("UNIT_AURA", "party2"); advance(1)')
check(picks(W9) == {"player": None, "party1": "Water Breathing", "party2": "Water Walking"},
      "Smart Y ON: per unit the first listed buff it lacks (you have both: none; party2 has Water Breathing: Water Walking)", picks(W9))
c = docs(W9, n0, 'partycfg')
check(c and set(c[-1]['c']['sy']['pick']) == {"party1", "party2"} and c[-1]['c']['sy']['pick']['party1']['id'] == 131 and c[-1]['c']['sy']['on'] is True,
      "partycfg sy.pick per unit token, sent when the picks change", c and c[-1]['c']['sy'].get('pick'))
W9.execute('WowCompanionSpellsDB.partyBy["Korvash-Realm"].buffs = { 1243, 131 }; fire("UNIT_AURA", "party1"); advance(1)')
check(picks(W9)["party1"] == "Water Breathing", "a listed buff this character does not know (Fortitude) is skipped", picks(W9))
W9.execute('WowCompanionSpellsDB.partyBy["Korvash-Realm"].buffs = { 131, 546 }')
W9.execute('AURAS.party1 = { "x", secret = true }; fire("UNIT_AURA", "party1"); advance(1)')
check(picks(W9)["party1"] is None, "a secret aura read: no pick for that unit", picks(W9))
W9.execute('AURAS.party1 = nil; fire("UNIT_AURA", "party1"); advance(1)')
# in a fight: no new picks (no attribute write); m132: the picks ARMED before it stay on the pad, partycfg keeps showing them, and Y
# with no In combat action casts the armed one on the picked member (no menu)
W9.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; AURAS.party1 = { "Water Breathing", "Water Walking" }; fire("UNIT_AURA", "party1"); advance(1)')
check(picks(W9)["party1"] == "Water Breathing" and W9.eval('#VIOL') == 0, "in a fight the picks are not touched (no lockdown breach)", picks(W9))
n0 = nsent(W9)
W9.execute('WowCompanion.snapshot("heartbeat"); advance(1)')
c = docs(W9, n0, 'partycfg')
check(c and c[-1]['c']['sy']['pick']['party1']['id'] == 131 and c[-1]['c']['sy']['pick']['party2']['id'] == 546,
      "m132: in a fight partycfg still carries sy.pick (the picks armed before it)", c and c[-1]['c']['sy'].get('pick'))
W9.execute('local c = WowCompanion.pad.rows.party.cells[10]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false); NOW = NOW + 0.3')
W9.execute('local p = rawget(_G, "WowCompanionPartyPad"); p.a["wc-py"] = nil; p.a["wc-box"] = 2; p.a["wc-u"] = "party1"; trig(false, false)')
a0 = nact(W9)
W9.execute('hit(6)')
check(acts(W9, a0) == [("spell", "Water Breathing", "party1")] and W9.eval('#VIOL') == 0,
      "m132: Party mode turned on IN the fight (col 10), Y with no In combat action: Smart Y's armed buff on party1", acts(W9, a0))
# m133 (review): the pick casts ONCE a fight per member (no aura read in the lockdown): the next Y targets, partycfg drops that pick
n0 = nsent(W9)
a0 = nact(W9)
W9.execute('hit(6)')
c = docs(W9, 0, 'partycfg')         # (sent at the FIRST Y's press, when its pick was used)
check(acts(W9, a0) == [("target", None, "party1")] and W9.eval('PADA("wc-pb-party1")') is None and W9.eval('#VIOL') == 0,
      "m133: a second Y on party1 in the same fight: Target (the buff is not cast again)", (acts(W9, a0), W9.eval('#VIOL')))
check(c and 'party1' not in (c[-1]['c']['sy'].get('pick') or {}) and (c[-1]['c']['sy'].get('pick') or {}).get('party2', {}).get('id') == 546,
      "m133: ... partycfg in the fight drops party1's used pick (the strip shows TARGET), party2's stays", c and c[-1]['c']['sy'].get('pick'))
# a roster change in the fight moves the party tokens: their picks are dropped (secure, the count driver)
W9.execute('UNITS.party3 = { name = "Doran", cls = "ROGUE", alive = true, role = "DAMAGER", pt = "ENERGY", guid = "Player-1-Doran" }; '
           'fire("GROUP_ROSTER_UPDATE"); driveStates(); advance(1)')
c = docs(W9, n0, 'partycfg')
check(W9.eval('PADA("wc-pb-party2")') is None and W9.eval('#VIOL') == 0 and c and not (c[-1]['c']['sy'].get('pick') or {}).get('party2'),
      "m133: a member joins in the fight: the party picks are dropped (tokens may have moved), partycfg follows", (W9.eval('PADA("wc-pb-party2")'), c and c[-1]['c']['sy'].get('pick')))
W9.execute('UNITS.party3 = nil; fire("GROUP_ROSTER_UPDATE"); driveStates()')
W9.execute('local c = WowCompanion.pad.rows.party.cells[11]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false); NOW = NOW + 0.3')
W9.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
check(picks(W9)["party1"] is None, "the fight's end: picks again (party1 now has both: none)", picks(W9))
check(picks(W9)["party2"] == "Water Walking", "m133: the fight's end: party2's dropped pick is armed again", picks(W9))
# PB select + ops, with the 2 s rule
W9.execute('WowCompanionSpellsDB.partyBy["Korvash-Realm"].buffs = { 131, 546 }')
tapcell(W9, 24); tapcell(W9, 32)                   # select row 2, up
check(buffs(W9) == [546, 131], "select row 2 (col 24) + Up (col 32): moved up", buffs(W9))
tapcell(W9, 23); tapcell(W9, 33)                   # select row 1, down
check(buffs(W9) == [131, 546], "select row 1 (col 23) + Down (col 33): moved down", buffs(W9))
tapcell(W9, 23); W9.execute('NOW = NOW + 2.5'); n0 = nsent(W9); tapcell(W9, 31)
ack = docs(W9, n0, 'partyack')
check(buffs(W9) == [131, 546] and ack and ack[0]['why'] == 'no row picked', "a select older than 2 s: Remove refused (no row picked)", ack)
tapcell(W9, 23); tapcell(W9, 23 + 5)               # a select of an empty row (6)
n0 = nsent(W9); tapcell(W9, 31)
ack = docs(W9, n0, 'partyack')
check(buffs(W9) == [131, 546] and ack and ack[0]['ok'] is False, "a select of an empty row: Remove refused", ack)
tapcell(W9, 23); n0 = nsent(W9); tapcell(W9, 31)
ack = docs(W9, n0, 'partyack')
check(buffs(W9) == [546] and ack and ack[0]['ok'] is True and ack[0]['op'] == 'pb' and ack[0]['id'] == 131, "select row 1 + Remove (col 31)", ack)
cfgs = docs(W9, n0, 'partycfg')
check(cfgs and [x['id'] for x in cfgs[-1]['c']['sy']['list']] == [546], "the list shown is partycfg.sy.list, sent after the op", cfgs and cfgs[-1]['c']['sy']['list'])
W9.execute('COMBAT = true')
n0 = nsent(W9); tapcell(W9, 23); tapcell(W9, 31)
ack = docs(W9, n0, 'partyack')
check(buffs(W9) == [546] and ack and ack[0]['why'] == 'combat', "a list op in a fight: refused (combat)", ack)
W9.execute('COMBAT = false')
# Smart Y off again: the picks go
W9.execute('WowCompanionSpellsDB.partyBy["Korvash-Realm"].buffs = { 131 }; AURAS = {}; fire("UNIT_AURA", "party1"); advance(1)')
check(picks(W9)["party1"] == "Water Breathing", "(on: party1 picked)")
W9.execute('WowCompanionSpellsDB.smartOn = {}; fire("UNIT_AURA", "party1"); advance(1)')
check(picks(W9) == {"player": None, "party1": None, "party2": None}, "Smart Y off: every pick cleared", picks(W9))
check(W9.eval('#SPY') == 0 and W9.eval('#VIOL') == 0, "no spied call, no lockdown breach")

# ================================================================ THE PARTY TAB (PadTabCtl p33 + Book b47's padtab 26-37)
check('local CV = "p50"' in SRC["padtabctl"] and 'local BV = "b56"' in SRC["book"] and 'local PV = "m154"' in SRC["padmap"],
      "the versions: PadMap m146, PadTabCtl p46, Book b52 (b49 stance-bind #179, b50 toolbar D-pad #18/#20, b51 / m143 / m144 rank up, m146 / p46 / b52 Smart Range)")
W8 = world(pre='BOOK = { 403, 331, 8004, 324, 131, 546 }')
W8.execute('''
local ctl = WowCompanion._spellsBook.ctl
ctl.croom, ctl.ctrlTab, ctl.onTabs = true, true, true
WowCompanion.padTabCtl.enter("tabs")
function ptab(col) WowCompanion.pad.rows.padtab.handler(nil, col); NOW = NOW + 0.05 end
function pp(key) return WowCompanion.padTabCtl.press(key) end
function spellTaps(id)                  -- Book's two taps for a spell (the spells row: vb<group> then vo<offset>)
    local v = WowCompanion.spellsPadNumbers({ id })[id]
    local cols = WowCompanion.spellsWinCols
    local on = WowCompanion.pad.rows.spells.onClick
    on(cols["vb" .. math.floor(v / 13)]); on(cols["vo" .. (v % 13)])
end
''')


def ctlDoc(L):
    return docs(L, 0, 'padtabctl')[-1]['c']


W8.execute('ptab(26)')
c = ctlDoc(W8)
check(c['view'] == 'party' and c['ppos'] == 8 and c['pfocus'] == 'tabs' and c['pset'] == 1 and c['ptouch'] == 1,
      "p37: padtab 26: the Party mode tab (the cursor still on the tabs); its place A (ppos 8 = the router's A), pset 1, ptouch 1", c)
moves = []
for k in ("PADDDOWN", "PADDDOWN", "PADDDOWN", "PADDUP", "PADDUP", "PADDUP", "PADDUP", "PADDDOWN", "PADDDOWN", "PADDDOWN"):
    W8.execute('pp("%s")' % k)
    moves.append((ctlDoc(W8)['pfocus'], ctlDoc(W8)['ppos']))
check(moves == [("sets", 8), ("touch", 8), ("diam", 8), ("diam", 5), ("touch", 5), ("sets", 5), ("tabs", 5), ("sets", 5), ("touch", 5), ("diam", 5)],
      "p37: Down from the tabs: the set row, the Touch row, the diamonds (A); Up: X (drawn on top), then back up the same way", moves)
W8.execute('pp("PADDRIGHT")')
check(ctlDoc(W8)['ppos'] == 8, "p37: Right from X: A (the diamond as drawn)", ctlDoc(W8)['ppos'])
W8.execute('pp("PAD1")')
c = ctlDoc(W8)
check(c['pfocus'] == 'right' and c['pright'] == 0, "A on A: the right page (pright 0-based: its first row)", c)
W8.execute('pp("PAD1")')
c = ctlDoc(W8)
check(c['chooser'] == {"list": "party", "row": 1, "id": "empty"}, "its red Change: the chooser, list party, the no-spell row first (empty)", c['chooser'])
W8.execute('pp("PADDDOWN")')
c = ctlDoc(W8)
check(c['chooser']['row'] == 2 and c['chooser']['id'] == 331, "Down: Healing Wave (partycfg.spells by id)", c['chooser'])
W8.execute('pp("PADDDOWN")')
check(ctlDoc(W8)['chooser']['id'] == 8004, "Down: Lesser Healing Wave", ctlDoc(W8)['chooser'])
n0 = nsent(W8)
W8.execute('pp("PAD1")')
ack = docs(W8, n0, 'partyack')
check(ack and ack[0]['op'] == 'slot' and ack[0]['slot'] == 'a' and ack[0]['id'] == 8004 and ack[0]['ok'] is True and ctlDoc(W8).get('chooser') is None
      and W8.eval('PADA("wc-ps-a")') == "Lesser Healing Wave", "A: A = Lesser Healing Wave (partyack slot ok; the chooser closes)", ack)
W8.execute('pp("PAD1")')
n0 = nsent(W8)
W8.execute('pp("PAD1")')
check(docs(W8, n0, 'partyack')[0]['ok'] is True and cfg(W8)['slots']['a'] is None, "the no-spell row: A = Empty", cfg(W8)['slots']['a'])
W8.execute('pp("PAD2")')
check(ctlDoc(W8)['pfocus'] == 'diam' and ctlDoc(W8)['ppos'] == 8, "B on the right page: back to the diamonds (A)", ctlDoc(W8))
# Y: Smart Y off -> one row (In combat: Change), the chooser's first row "inspect"
W8.execute('pp("PADDLEFT"); pp("PAD1"); pp("PAD1")')
check(ctlDoc(W8)['ppos'] == 6 and ctlDoc(W8)['chooser']['id'] == 'inspect', "Y (Left from A; Smart Y off): Change = the In combat action, first row inspect",
      ctlDoc(W8).get('chooser'))
W8.execute('pp("PADDDOWN"); pp("PAD1")')
check(cfg(W8)['yc'] is not None and int(cfg(W8)['yc']['id']) == 331, "Y's In combat = Healing Wave", cfg(W8)['yc'])
W8.execute('pp("PAD2")')
# Y with Smart Y on: the party list's rows, Add, In combat
W8.execute('WowCompanionSpellsDB.smartOn = { ["Korvash-Realm"] = true }; WowCompanionSpellsDB.partyBy["Korvash-Realm"].buffs = { 131 }')
W8.execute('pp("PAD1")')
W8.execute('pp("PADDDOWN")')
c = ctlDoc(W8)
check(c['pfocus'] == 'right' and c['pright'] == 1, "Smart Y on: Y's right page has rows (the buff, Add, In combat): Add = pright 1 (0-based)", c)
W8.execute('pp("PAD1")')
c = ctlDoc(W8)
check(c['chooser']['list'] == 'pbuff' and c['chooser']['id'] == 546, "Add: the chooser of party buffs known and not listed (Water Walking)", c['chooser'])
W8.execute('pp("PAD1")')
check(buffs(W8) == [131, 546], "A: Water Walking added", buffs(W8))
W8.execute('pp("PADDUP"); pp("PADDUP"); pp("PADDUP")')
n0 = nsent(W8)
W8.execute('pp("PAD1")')
check(buffs(W8) == [546] and docs(W8, n0, 'partyack')[0]['op'] == 'pb', "on the first buff row the red action is Remove", buffs(W8))
W8.execute('pp("PADDDOWN"); pp("PADDDOWN"); pp("PAD1")')
check(ctlDoc(W8)['chooser']['list'] == 'party' and ctlDoc(W8)['chooser']['id'] == 'inspect', "the last row: In combat (Change)", ctlDoc(W8)['chooser'])
W8.execute('pp("PAD2"); pp("PAD2"); WowCompanionSpellsDB.smartOn = {}')
# p37 THE LOCKED PLACES: the D-pad (p38: all four directions move the pick, the user's adaptive rows 2026-11-11), B, Hold, ZL + ZR
W8.execute('pp("PADDLEFT"); pp("PAD1")')
check(ctlDoc(W8)['ppos'] == 3 and ctlDoc(W8)['note'] == 'locked' and ctlDoc(W8)['pfocus'] == 'diam',
      "p38: the D-pad's Right: locked (it moves the pick, like Up / Down), no page", ctlDoc(W8))
W8.execute('pp("PADDUP"); pp("PAD1")')
check(ctlDoc(W8)['ppos'] == 2 and ctlDoc(W8)['note'] == 'locked', "p37: the D-pad's Up: locked (it moves the pick)", ctlDoc(W8))
W8.execute('pp("PADDDOWN"); pp("PADDRIGHT"); pp("PADDRIGHT"); pp("PADDRIGHT"); pp("PADDDOWN"); pp("PAD1")')
check(ctlDoc(W8)['ppos'] == 7 and ctlDoc(W8)['note'] == 'locked', "p37: B: locked (the red Back)", ctlDoc(W8))
W8.execute('pp("PADDUP"); pp("PADDUP")')
check(ctlDoc(W8)['pfocus'] == 'touch' and ctlDoc(W8)['ptouch'] == 1, "p37: Up off the diamonds' top: the Touch row (Touch)", ctlDoc(W8))
W8.execute('pp("PADDRIGHT"); pp("PAD1")')
check(ctlDoc(W8)['ptouch'] == 2 and ctlDoc(W8)['note'] == 'locked', "p37: Hold (the player menu): locked", ctlDoc(W8))
W8.execute('pp("PADDLEFT"); pp("PAD1"); pp("PAD1")')
check(ctlDoc(W8)['chooser'] == {"list": "party", "row": 1, "id": "target"}, "p37: Touch: its page's Change, the chooser's first row Target",
      ctlDoc(W8).get('chooser'))
W8.execute('pp("PAD2"); pp("PAD2")')
check(ctlDoc(W8)['pfocus'] == 'touch', "p37: B, B: back on the Touch row", ctlDoc(W8)['pfocus'])
W8.execute('pp("PADDUP"); pp("PADDRIGHT"); pp("PADDRIGHT"); pp("PAD1")')
check(ctlDoc(W8)['pfocus'] == 'sets' and ctlDoc(W8)['scur'] == 4 and ctlDoc(W8)['note'] == 'locked' and ctlDoc(W8)['pset'] == 1,
      "p37: the set row's ZL + ZR: locked (nothing happens with both held)", ctlDoc(W8))
W8.execute('pp("PADDLEFT"); pp("PAD1")')
check(ctlDoc(W8)['pset'] == 3, "p37: A on ZR: the ZR layer on show (pset 3)", ctlDoc(W8))
W8.execute('pp("PADDDOWN"); pp("PADDDOWN"); pp("PAD1"); pp("PAD1"); pp("PADDDOWN"); pp("PAD1")')
check(ctlDoc(W8)['ppos'] == 5 and W8.eval('PADA("wc-ps-zrx")') == "Healing Wave", "p37: on the ZR layer, X = ZR + X: Healing Wave put there",
      (ctlDoc(W8)['ppos'], W8.eval('PADA("wc-ps-zrx")')))
W8.execute('pp("PAD2"); pp("PADDUP"); pp("PADDUP"); pp("PAD1")')
check(ctlDoc(W8)['pset'] == 1 and ctlDoc(W8)['pfocus'] == 'sets', "p37: A on ZR again: the base layer", ctlDoc(W8))
W8.execute('pp("PADDDOWN"); pp("PADDDOWN")')
# TOUCH: padtab 27-36 picks + 37 / Book's two-tap spell
n0 = nsent(W8)
W8.execute('ptab(27 + 8); spellTaps(331)')              # slot 9 = zrx, then Healing Wave by the two taps
ack = docs(W8, n0, 'partyack')
check(ack and ack[0]['slot'] == 'zrx' and ack[0]['id'] == 331 and ack[0]['ok'] is True and W8.eval('PADA("wc-ps-zrx")') == "Healing Wave",
      "padtab 35 (slot zrx) + Book's two taps for Healing Wave: ZR+X = Healing Wave", ack)
check(ctlDoc(W8)['ppos'] == 5 and ctlDoc(W8)['pset'] == 3, "the pick moved the cursor (p37: X on the ZR layer)", ctlDoc(W8))
W8.execute('ptab(30)')
W8.execute('spellTaps(8004)')
check(int(cfg(W8)['touch']['id']) == 8004 and W8.eval('WowCompanion.pad.rows.party.cells[1].a["wc-touch"]') == "Lesser Healing Wave",
      "padtab 30 (touch) + a spell: Touch = Lesser Healing Wave (m130: the pick cells follow)", cfg(W8)['touch'])
W8.execute('ptab(30); ptab(37)')
check(cfg(W8)['touch'] is None and W8.eval('WowCompanion.pad.rows.party.cells[1].a["wc-touch"]') is None, "padtab 30 + 37: Touch = Target", cfg(W8)['touch'])
W8.execute('ptab(28); ptab(37)')
check(cfg(W8)['slots']['x'] is None and W8.eval('WowCompanionSpellsDB.partyBy["Korvash-Realm"].slots.x') is False, "padtab 28 + 37: X = Empty")
n0 = nsent(W8)
W8.execute('ptab(27); NOW = NOW + 2.5; spellTaps(331)')
check(docs(W8, n0, 'partyack') == [] and ctlDoc(W8)['note'] == 'pslot-no-pick', "a slot pick older than 2 s: the spell is refused (no pick)",
      ctlDoc(W8)['note'])
W8.execute('ptab(37)')
check(ctlDoc(W8)['note'] == 'pslot-no-pick', "37 with no fresh pick: nothing", ctlDoc(W8)['note'])
n0 = nsent(W8)
W8.execute('ptab(39)')
pa = docs(W8, n0, 'padack')
# Book b52 (PadMap m146 SMART RANGE): padtab 39 is the Smart Range pick now (its code 0-4 follows); alone it is no game action
check(pa == [] and W8.eval('WowCompanion.padTabCtl.srWants()') is True, "padtab 39: the Smart Range pick (Book b52), no game action, no refusal", pa)
W8.execute('ptab(29); COMBAT = true')
n0 = nsent(W8)
W8.execute('spellTaps(331)')
ack = docs(W8, n0, 'partyack')
check(ack and ack[0]['why'] == 'combat' and ack[0]['slot'] == 'yc', "a touch put in a fight: partyack why combat", ack)
W8.execute('COMBAT = false')
W8.execute('ptab(0)')
check(ctlDoc(W8)['view'] == 'normal' and 'ppos' not in ctlDoc(W8), "padtab 0: back to WoW Defaults (no party fields)", ctlDoc(W8)['view'])
# p37 (party_design.md B): the Party mode tab only while Party mode is allowed (WC.partyOk, Hub's experimental switch)
W8.execute('WowCompanion.partyOk = false; ptab(26)')
check(ctlDoc(W8)['view'] == 'normal', "p37: Party mode not allowed: padtab 26 opens WoW Defaults (the tab is hidden)", ctlDoc(W8)['view'])
W8.execute('pp("PADRSHOULDER"); pp("PADRSHOULDER"); pp("PADRSHOULDER")')
check(ctlDoc(W8)['view'] == 'radial', "p37: ... and RB stops at the Spell radial (no Party mode tab past it)", ctlDoc(W8)['view'])
W8.execute('WowCompanion.partyOk = true; pp("PADRSHOULDER")')
check(ctlDoc(W8)['view'] == 'party', "p37: allowed again: RB from the Spell radial reaches Party mode", ctlDoc(W8)['view'])
W8.execute('ptab(0)')
check(W8.eval('#SPY') == 0 and W8.eval('#VIOL') == 0 and list(W8.eval('ERRS').values()) == [], "no spied call, no lockdown breach, no error")

# ================================================================ 11. QUESTS: THE PLAYER MENU FOR A PARTY UNIT (q49)
import re as _re
_qv = _re.search(r'local QV = "q(\d+)"', SRC["quests"])
check(_qv is not None and int(_qv.group(1)) >= 55, "Quests says q55 or later", _qv and _qv.group(0))
MENU = r'''
CALLS, CLICKED = {}, {}
local function mixin(name, text, enabled)
    local m = { GetText = function() return text end, CanShow = function() return true end,
                IsEnabled = function() return enabled ~= false end }
    m.OnClick = function() CLICKED[#CLICKED + 1] = name end
    rawset(_G, "UnitPopup" .. name .. "ButtonMixin", m)
    return m
end
ENTRIES = { mixin("SetFocus", "Set Focus"), mixin("Whisper", "Whisper"), mixin("Inspect", "Inspect"), mixin("Trade", "Trade"),
            mixin("Follow", "Follow"), mixin("Duel", "Duel"), mixin("PromoteToLeader", "Promote to Party Leader"),
            mixin("Uninvite", "Uninvite"), mixin("RaidTarget8", "Skull"), mixin("ClearFocus", "Clear Focus") }
UnitPopupManager = { GetMenu = function(_, which) MENUWHICH = which; return { GetEntries = function() return ENTRIES end } end }
for _, n in ipairs({ "InitiateTrade", "FollowUnit", "PromoteToLeader", "SetRaidTarget", "UninviteUnit", "StartDuel" }) do
    _G[n] = function(...) CALLS[#CALLS + 1] = { n, ... } end
end
C_PartyInfo = { UninviteUnit = function(...) CALLS[#CALLS + 1] = { "UninviteUnit", ... } end }
function GetRaidTargetIndex() return 0 end
'''
Q = boot(["safety", "tappad", "quests"], pre=MENU)
Q.execute(WORLD_HELPERS)
Q.eval('party')(Q.table_from([Q.table_from(["Brannoc", "WARRIOR"]), Q.table_from(["Iselle", "MAGE"])]))
n0 = nsent(Q)
Q.eval('WowCompanion.pmenu.read')("party2", "party")
pm = docs(Q, n0, 'pmenu')
check(len(pm) == 1 and pm[0]['unit'] == 'party2' and pm[0]['which'] == 'PARTY' and pm[0]['src'] == 'party' and pm[0]['nm'] == 'Iselle'
      and _re.match(r'^q(\d+)$', str(pm[0]['v'])) and int(str(pm[0]['v'])[1:]) >= 55 and Q.eval('MENUWHICH') == 'PARTY', "read(\"party2\", \"party\"): the PARTY menu; doc unit, which PARTY, nm, src party",
      pm and {k: pm[0].get(k) for k in ('unit', 'which', 'nm', 'src')})
items = {x['t']: x for x in pm[0]['items']}
check(items['Set Focus'].get('sc') == 14 and items['Set Focus'].get('scr') == 'party', "Set Focus = the PARTY row's focus cell for party2 (col 12 + 2)",
      items['Set Focus'])
check(items['Clear Focus'].get('sc') == 1 and 'scr' not in items['Clear Focus'], "Clear Focus stays Quests' pfocus col 1", items['Clear Focus'])
check(items['Inspect']['en'] == 0 and 'i' not in items['Inspect'], "Inspect is greyed (InspectUnit taints)", items['Inspect'])
check(items['Duel']['en'] == 1 and 'i' in items['Duel'], "q51: Duel runs (StartDuel on the unit)", items['Duel'])
check(items['Whisper'].get('ap') == 'whisper' and 'i' in items['Whisper'], "Whisper: ap whisper (and runnable: it sets the reply target)", items['Whisper'])
Q.eval('WowCompanion.pmenu.run')(items['Whisper']['i'])
check(Q.eval('WowCompanion.chatLastTell') == 'Iselle', "running Whisper sets WC.chatLastTell = the member", Q.eval('WowCompanion.chatLastTell'))
# q50: this build's InitiateTrade takes the member's GUID (the unit only on a build that refuses it)
for t, want in (("Trade", ["InitiateTrade", "Player-1-Iselle"]), ("Follow", ["FollowUnit", "party2"]), ("Skull", ["SetRaidTarget", "party2", 8])):
    c0 = int(Q.eval('#CALLS'))
    Q.eval('WowCompanion.pmenu.run')(items[t]['i'])
    got = list(Q.eval('CALLS[#CALLS]').values()) if int(Q.eval('#CALLS')) > c0 else None
    check(got == want, "%s runs the game's plain call on the unit: %s" % (t, want), got)
check(items['Uninvite'].get('cf') is not None and items['Promote to Party Leader'].get('cf') is not None, "Uninvite / Promote ask first (cf)")
check(int(Q.eval('#CLICKED')) == 0, "no Blizzard menu code (an entry's OnClick) ever ran")
Q.execute('advance(1)')
pm2 = docs(Q, n0, 'pmenu')
check(len(pm2) >= 2 and pm2[-1]['unit'] == 'party2', "after a run the SAME unit's menu is read again", pm2[-1].get('unit'))
check(pm2[-1].get('again') == 1 and 'again' not in pm2[0], "q54: that re-read says again = 1 (the app never opens a wheel for it)", pm2[-1].get('again'))
n0 = nsent(Q)
Q.eval('WowCompanion.pmenu.read')()
pm = docs(Q, n0, 'pmenu')[0]
items = {x['t']: x for x in pm['items']}
check(pm['unit'] == 'player' and pm['which'] == 'SELF' and 'src' not in pm and Q.eval('MENUWHICH') == 'SELF' and items['Set Focus'].get('sc') == 0
      and 'scr' not in items['Set Focus'], "read() as before (the map's portrait): SELF, no src, Set Focus = pfocus col 0", pm.get('which'))
n0 = nsent(Q)
Q.eval('WowCompanion.pmenu.read')("player", "party")
items = {x['t']: x for x in docs(Q, n0, 'pmenu')[0]['items']}
check(items['Set Focus'].get('sc') == 12 and items['Set Focus'].get('scr') == 'party', "your own cell's menu in Party mode: Set Focus = party col 12",
      items['Set Focus'])
# q51: the TARGET's menu for the app's radial (src "radial"): a friendly player, then an NPC
Q.execute('function UnitIsUnit(a, b) return a == b end; function UnitInParty() return false end; '
          'function UnitIsPlayer(u) return TPLAYER end; function UnitCanCooperate() return true end; TPLAYER = true')
n0 = nsent(Q)
Q.eval('WowCompanion.pmenu.read')("target", "radial")
pm = docs(Q, n0, 'pmenu')[0]
items = {x['t']: x for x in pm['items']}
check(pm['unit'] == 'target' and pm['which'] == 'PLAYER' and pm['src'] == 'radial' and Q.eval('MENUWHICH') == 'PLAYER',
      "q51: read(\"target\", \"radial\"): a friendly player = the PLAYER menu, src radial", {k: pm.get(k) for k in ('unit', 'which', 'src')})
check(items['Inspect'].get('sc') == 2 and items['Inspect']['en'] == 1, "q51: the target's Inspect = pfocus col 2 (the game's /inspect)", items['Inspect'])
check(items['Set Focus'].get('sc') == 3 and 'scr' not in items['Set Focus'], "q51: Set Focus on the target = pfocus col 3", items['Set Focus'])
check(items['Trade'].get('sc') == 4 and 'i' not in items['Trade'], "q55: the target wheel's Trade = pfocus col 4 (the game's /trade)", items['Trade'])
c0 = int(Q.eval('#CALLS'))
Q.eval('WowCompanion.pmenu.run')(items['Duel']['i'])
got = list(Q.eval('CALLS[#CALLS]').values()) if int(Q.eval('#CALLS')) > c0 else None
check(got == ["StartDuel", "target"], "q51: Duel = StartDuel(\"target\")", got)
Q.execute('TPLAYER = false')
n0 = nsent(Q)
Q.eval('WowCompanion.pmenu.read')("target", "radial")
pm = docs(Q, n0, 'pmenu')[0]
check(pm['which'] == 'TARGET' and Q.eval('MENUWHICH') == 'TARGET', "q51: an NPC target = the TARGET menu", pm.get('which'))
check(int(Q.eval('#CLICKED')) == 0, "q51: still no Blizzard menu code ran")
# q52 (the user on test24: "showing half of it blank for my party members"): a party member targeted = that member's own menu
Q.execute('TPLAYER = true; function UnitInParty() return true end; '
          'function UnitIsUnit(a, b) return a == b or (a == "target" and b == "party2") end')
n0 = nsent(Q)
Q.eval('WowCompanion.pmenu.read')("target", "radial")
pm = docs(Q, n0, 'pmenu')[0]
items = {x['t']: x for x in pm['items']}
check(pm['unit'] == 'party2' and pm['which'] == 'PARTY' and pm['src'] == 'radial' and Q.eval('MENUWHICH') == 'PARTY',
      "q52: a party member targeted: the PARTY menu for party2, src radial", {k: pm.get(k) for k in ('unit', 'which', 'src')})
check(items['Inspect'].get('sc') == 2 and items['Set Focus'].get('sc') == 3 and 'scr' not in items['Set Focus'],
      "q52: the radial's Inspect / Set Focus act on the target (pfocus 2 / 3)", (items['Inspect'], items['Set Focus']))
Q.execute('function UnitInParty() return false end; function UnitIsUnit(a, b) return a == b end')
# q53 (the user on test25: "so many options are still not showing up things like trade"): the menu read as the game opens it -
# AssembleMenuEntries first, and the context the entries test (playerLocation, accountInfo, isMobile, name by UnitNameUnmodified)
Q.execute(r'''
ORIGMGR = UnitPopupManager
local function mk(name, text) local m = { GetText = function() return text end, CanShow = function() return true end,
    IsEnabled = function() return true end, OnClick = function() CLICKED[#CLICKED + 1] = name end }
    rawset(_G, "UnitPopup" .. name .. "ButtonMixin", m); return m end
local trade = mk("Trade2", "Trade")
local fr = mk("AddFriend", "Add Friend")
fr.CanShow = function(_, cd) return cd.playerLocation ~= nil and cd.playerLocation.ok == true end     -- errors / false without it
UnitPopupSharedUtil = { TryCreatePlayerLocation = function(cd) return { ok = true, unit = cd.unit } end,
                        GetBNetAccountInfo = function() return nil end, GetIsMobile = function() return false end }
function UnitNameUnmodified(u) return "Brannoc", nil end
UnitPopupManager = { GetMenu = function(_, which) MENUWHICH = which
    return { GetEntries = function() return { ENTRIES[2] } end,
             AssembleMenuEntries = function(_, cd) ASSEMBLED = cd; return { ENTRIES[2], trade, fr } end } end }
''')
n0 = nsent(Q)
Q.eval('WowCompanion.pmenu.read')("target", "radial")
pm = docs(Q, n0, 'pmenu')[0]
ts = [x['t'] for x in pm['items']]
check(ts == ['Whisper', 'Trade', 'Add Friend'], "q53: the entries come from AssembleMenuEntries (Trade, Add Friend shown)", ts)
check(Q.eval('ASSEMBLED.unit') == 'target' and Q.eval('ASSEMBLED.fromTargetFrame') is True and Q.eval('ASSEMBLED.name') == 'Brannoc',
      "q53: the context the game builds: unit, fromTargetFrame, name by UnitNameUnmodified, playerLocation",
      (Q.eval('ASSEMBLED.unit'), Q.eval('ASSEMBLED.fromTargetFrame'), Q.eval('ASSEMBLED.name')))
Q.execute('UnitPopupManager = ORIGMGR; UnitPopupSharedUtil = nil; UnitNameUnmodified = nil')     # the fixtures below read the plain menu
# the party pad's Y and the party row's MENU cells read the menu for the unit, src "party"
W11 = world()
W11.execute('PMREADS = {}; WowCompanion.pmenu = { read = function(u, s) PMREADS[#PMREADS + 1] = { u, s } end }')
W11.eval('party')(W11.table_from([W11.table_from(m) for m in MEMBERS[:2]]))
flip(W11, "WowCompanionPartyOn")
W11.execute('rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 3; hit(6)')
r = W11.eval('PMREADS[#PMREADS]')
check(r is not None and list(r.values()) == ["party2", "party"], "Y (no pick): WC.pmenu.read(\"party2\", \"party\")", r and list(r.values()))
W11.execute('local c = WowCompanion.pad.rows.party.cells[18]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false)')
r = W11.eval('PMREADS[#PMREADS]')
check(list(r.values()) == ["party1", "party"], "party row col 18 (MENU party1): WC.pmenu.read(\"party1\", \"party\")", list(r.values()))

# ================================================================ CROSS-SIDE FIXTURES (INTEGRATION): the documents the Lua REALLY sends
# The raw strings out of Safety.lua's encoder (nil = absent, {} = [] as the add-on encodes them), kept for the app's JVM test
# (dsforever/src/test/.../PartyFixturesTest.kt reads dsforever/src/test/resources/party-fixtures/*.json). The committed files must equal
# what this run emits: PARTY_FIXTURES_WRITE=1 rewrites them.
FIXDIR = os.path.join(ROOT, "dsforever", "src", "test", "resources", "party-fixtures")
FIX = {}


def raw(L, start, typ):
    out = []
    for i in range(start + 1, int(L.eval('#SENT')) + 1):
        t = L.eval('SENT[%d]' % i)
        try:
            d = json.loads(t)
        except Exception:
            continue
        if isinstance(d, dict) and d.get('type') == typ:
            out.append(t)
    return out


def keep(name, L, start, typ, which=-1):
    r = raw(L, start, typ)
    check(bool(r), "fixture %s: the add-on sent a %s" % (name, typ))
    if r:
        FIX[name] = r[which]


def tap_party(col):
    return 'local c = WowCompanion.pad.rows.party.cells[%d]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false)' % col


# a solo Shaman, Party mode off; the heartbeat ride: party + partycfg (class defaults, Smart Y's list empty, no yc / touch / pick)
FX = world()
n0 = nsent(FX)
FX.eval('WowCompanion.snapshot')("heartbeat")
keep("party_solo_off", FX, n0, 'party')
keep("partycfg_shaman_default", FX, n0, 'partycfg')
# a Warrior with nothing chosen and no class default: every slot Empty ("slots":[]), no party spell
FW = world(pre='CLASS = "WARRIOR"; BOOK = { 78 }')
n0 = nsent(FW)
FW.eval('WowCompanion.snapshot')("heartbeat")
keep("partycfg_warrior_empty", FW, n0, 'partycfg')
check('"slots":[]' in FIX.get("partycfg_warrior_empty", ""), "the Warrior's partycfg says \"slots\":[] (an empty table)",
      FIX.get("partycfg_warrior_empty", "")[:160])
# a party of three; Party mode on; the presses: Down (move), A (cast), ZL+A Empty (none), Y with no pick (menu), a press in the menu
# (key), B in the menu (key PAD2), MENU DONE, B (off)
FX.eval('party')(FX.table_from([FX.table_from(["Brannoc", "WARRIOR", True, "TANK", "RAGE"]),
                                FX.table_from(["Iselle", "MAGE", True, "DAMAGER", "MANA"])]))
FX.execute('fire("GROUP_ROSTER_UPDATE"); advance(1)')
n0 = nsent(FX)
flip(FX, "WowCompanionPartyOn")
keep("partybox_on", FX, n0, 'partybox')
keep("party_three_on", FX, n0, 'party')
for name, lua in (("partybox_move", 'hit(4)'), ("partybox_cast", 'hit(8)'), ("partybox_none_zl", 'trig(true, false); hit(8); trig(false, false)'),
                  ("partybox_menu", 'hit(6)'), ("partybox_key_down", 'hit(4)'), ("partybox_key_b", 'hit(7)')):
    n0 = nsent(FX)
    FX.execute(lua + '; advance(0.6)')
    keep(name, FX, n0, 'partybox')
FX.execute('clickName("WowCompanionPartyMenuDone", true); clickName("WowCompanionPartyMenuDone", false); advance(0.3)')
n0 = nsent(FX)
FX.execute('hit(7); advance(0.6)')
keep("partybox_off", FX, n0, 'partybox')
keep("party_three_off", FX, n0, 'party')
# the answers: RESEND, a spare column (op cell), a stale TSP tap (op touch why target), a setting in a fight (why combat), a slot set
for name, lua in (("partyack_resend", tap_party(22)), ("partyack_cell", tap_party(37)),
                  ("partyack_combat", 'COMBAT = true; WowCompanion.padMap.party.put("a", 331); COMBAT = false'),
                  ("partyack_slot_ok", 'WowCompanion.padMap.party.put("a", 331)')):
    n0 = nsent(FX)
    FX.execute(lua + '; NOW = NOW + 0.3')
    keep(name, FX, n0, 'partyack', 0)
# m129: a plate's taps with Party mode on: another member's = the pick moves (k pick); the picked one's = Touch (k touch)
flip(FX, "WowCompanionPartyOn")
for name, col in (("partybox_pick", 2), ("partybox_touch", 2)):
    n0 = nsent(FX)
    FX.execute(tap_party(col) + '; NOW = NOW + 0.3')
    keep(name, FX, n0, 'partybox')
flip(FX, "WowCompanionPartyOff")
# m131: the pet out (the shape "you, pet, party1, party2": n 4; m134 rows you pet | party1 | party2): the party document with the pet's
# entry (m134: its portrait icon ic), and a Right from you onto the pet (m134: you | pet share the first row; partybox box 2, u pet, cnt 4)
FX.execute('UNITS.pet = { name = "Wolf", alive = true, icon = 132242 }; fire("UNIT_PET", "player"); advance(1); rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 1')
n0 = nsent(FX)
flip(FX, "WowCompanionPartyOn")
keep("party_pet_on", FX, n0, 'party')
n0 = nsent(FX)
FX.execute('hit(3); advance(0.6)')
keep("partybox_pet_move", FX, n0, 'partybox')
flip(FX, "WowCompanionPartyOff")
FX.execute('UNITS.pet = nil; fire("UNIT_PET", "player"); advance(1)')
# m131: the geometric NAV table (built above from the rows), for PartyModel.nav
FIX["party_nav"] = NAV_FIXTURE
# partycfg with everything set: Smart Y on, a party list with picks, Y's In combat, Touch = a spell, a chosen Empty
FB = world(pre='BOOK = { 403, 331, 8004, 324, 131, 546 }')
FB.eval('party')(FB.table_from([FB.table_from(["Brannoc", "WARRIOR"]), FB.table_from(["Iselle", "MAGE"])]))
FB.execute('fire("GROUP_ROSTER_UPDATE"); advance(1)')
FB.execute('WowCompanion.padMap.party.put("yc", 331); WowCompanion.padMap.party.put("touch", 8004); WowCompanion.padMap.party.put("x", false)')
FB.execute('WowCompanionSpellsDB.smartOn = { ["Korvash-Realm"] = true }; WowCompanionSpellsDB.partyBy["Korvash-Realm"].buffs = { 131, 546 }')
FB.execute('AURAS = { player = { "Water Breathing", "Water Walking" }, party2 = { "Water Breathing" } }')
n0 = nsent(FB)
FB.execute('fire("UNIT_AURA", "party2"); advance(1); WowCompanion.snapshot("manual")')
keep("partycfg_full", FB, n0, 'partycfg')
n0 = nsent(FB)
FB.execute(tap_party(23) + '; NOW = NOW + 0.3')
FB.execute(tap_party(33) + '; NOW = NOW + 0.3')
keep("partyack_pb", FB, n0, 'partyack', 0)
# the Controller room's Party tab (padtabctl): the A row's right page, the chooser "party", Y's page with Smart Y on (pright = Add),
# the chooser "pbuff"
FB.execute('local ctl = WowCompanion._spellsBook.ctl; ctl.croom, ctl.ctrlTab, ctl.onTabs = true, true, true; '
           'WowCompanion.padTabCtl.enter("tabs"); WowCompanion.pad.rows.padtab.handler(nil, 26); NOW = NOW + 0.05')


def pp(k):
    FB.execute('WowCompanion.padTabCtl.press("%s")' % k)


# p37: down to the diamonds, the cursor on A (the router's 8)
pp("PADDDOWN"); pp("PADDDOWN"); pp("PADDDOWN")
n0 = nsent(FB); pp("PAD1"); keep("padtabctl_party_right", FB, n0, 'padtabctl')
n0 = nsent(FB); pp("PAD1"); pp("PADDDOWN"); keep("padtabctl_party_chooser", FB, n0, 'padtabctl')
pp("PAD2"); pp("PAD2"); pp("PADDLEFT")                                    # back to the diamonds, Left to Y
n0 = nsent(FB); pp("PAD1"); pp("PADDDOWN"); pp("PADDDOWN"); keep("padtabctl_party_y_add", FB, n0, 'padtabctl')
n0 = nsent(FB); pp("PAD1"); keep("padtabctl_party_pbuff", FB, n0, 'padtabctl')
FX2 = json.loads(FIX.get("padtabctl_party_y_add", "{}")).get('c', {})
check(FX2.get('ppos') == 6 and FX2.get('pfocus') == 'right' and FX2.get('pright') == 2,
      "the fixture's Y page: ppos 6 (p37: the router's Y), pfocus right, pright 2 (0-based: buff, buff, ADD)", {k: FX2.get(k) for k in ('ppos', 'pfocus', 'pright')})
# the player menu for a party member (Quests q49)
n0 = nsent(Q)
Q.eval('WowCompanion.pmenu.read')("party2", "party")
keep("pmenu_party2", Q, n0, 'pmenu')

os.makedirs(FIXDIR, exist_ok=True)
stale = []
for name, text in sorted(FIX.items()):
    fp = os.path.join(FIXDIR, name + ".json")
    old = open(fp, encoding='utf-8').read() if os.path.isfile(fp) else None
    if os.environ.get("PARTY_FIXTURES_WRITE") == "1":
        with open(fp, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(text)
    elif old != text:
        stale.append(name)
check(not stale, "the committed fixtures equal what the add-on sends now (%d files; PARTY_FIXTURES_WRITE=1 rewrites)" % len(FIX), stale[:4])

# ================================================================ THE NO-TRACE SCAN (new code only: the diff against the contract's base)
import subprocess
try:
    diff = subprocess.run(['git', '-C', ROOT, 'diff', '5ee1ff22', '--', 'companion/app/src/main/assets/companion/addons'],
                          capture_output=True, text=True, encoding='utf-8').stdout
except Exception as e:                                                    # no git: scan nothing (said)
    diff = ''
    print('NOTE no git diff: ' + str(e))
added = [ln[1:] for ln in diff.splitlines() if ln.startswith('+') and not ln.startswith('+++')]
# 1.0.30 test build merge (code 1453): the default diff can pair a merged file's lines differently and show an old line as "added"
# with its twin "removed" elsewhere; a line the base already had as often is not new code (multiset: added minus removed)
from collections import Counter
_gone = Counter(ln[1:].strip() for ln in diff.splitlines() if ln.startswith('-') and not ln.startswith('---'))
_kept = []
for ln in added:
    if _gone[ln.strip()] > 0:
        _gone[ln.strip()] -= 1
    else:
        _kept.append(ln)
added = _kept
code = [re.sub(r'--.*$', '', ln) for ln in added]
# m132 (the user, 2026-11-11: "Build a fight-only layer"): `self:SetBinding(` inside the header's own secure state snippet is the
# restricted handle's OVERRIDE binding on that header (never saved, cleared with it), not the game's saved SetBinding; m133: the same
# header handle reached as `h` (OV.fbSnip: the caller's "wc-hdr" ref, or the header itself)
BAD = [r'(?<!self:)(?<!h:)\bSetBinding\s*\(', r'\bSaveBindings\b', r'\bCreateMacro\b', r'\bEditMacro\b', r'\bSetCVar\b', r'\bPlaceAction\b',
       r'\bPickupAction\b', r'\bPickupMacro\b', r'\bShowUIPanel\b', r'\bHideUIPanel\b', r'\bToggleFrame\b', r'\bInspectUnit\b',
       r'\bUnitPopup_OpenMenu\b', r'\bTargetFrame_OpenMenu\b', r'\bToggleGameMenu\b', r'\bCloseAllBags\b', r'\bOpenAllBags\b',
       r'\bSetOverrideBinding\s*\(']
hits = [(p, ln.strip()) for ln in code for p in BAD if re.search(p, ln)]
# the user's one exception (2026-11-04, "Let DS Forever set it"): Chat.lua chat27 sets the chat style to Classic once
hits = [(p, ln) for (p, ln) in hits if not (p == r'\bSetCVar\b' and ('STYLE_CVAR' in ln or 'C_CVar.SetCVar) == "function"' in ln or '"gxCursor"' in ln
                                                                  # hub22 (the user, 2026-11-07): the hunter's own Combat Auto Shot option, asked for by name
                                                                  or 'autoRangedCombat' in ln or ln == 'local set = (C_CVar and C_CVar.SetCVar) or _G.SetCVar'
                                                                  # Trail ag (the user, 2026-11-07, "Turn on friendly nameplates"): the friendly nameplate options once
                                                                  or 'SetCVar(name, "1")' in ln or 'SetCVar(name, v)' in ln))]
# (and pad14's software cursor: the user's "Yes, try software cursor", 2026-11-04, gxCursor once)
# PadMap m143 RANK UP (the user, 2026-11-12, "Prompt, A upgrades all"): the player's own A on Home's prompt puts the new rank on the slots that
# held a lower one - a player-approved bar write exactly like Book's Bind (tools/test_rankup.py proves it runs only after that A)
hits = [(p, ln) for (p, ln) in hits if not (p == r'\bPlaceAction\b' and ln == 'CALL(function() return PlaceAction(c.s) end)')]
# PadMap m146 SMART RANGE (the user, 2026-11-12: "I am completely fine with saving macros for the addon"; "Hide text and sound"): OV.srSync
# makes / rewrites / renames ONLY our own "DSF ..." character macros (name AND body), out of combat; DSF_SR0 / DSF_SR1 turn the error
# speech off for the press and give the player's own value back (tools/test_smart_range.py proves both)
SR_OK = {'if body ~= w.b then CALL(function() return EditMacro(name, name, DYNAMIC_ICON, w.b) end); changed = true end',
         'CALL(function() return EditMacro(old, name, DYNAMIC_ICON, w.b) end); mine[old] = nil; changed = true',
         'CALL(function() return CreateMacro(name, DYNAMIC_ICON, w.b, true) end); changed = true',
         'if type(cc) == "table" and type(cc.SetCVar) == "function" then pcall(cc.SetCVar, cv, v) return end',
         'pcall(SetCVar, cv, v)'}
hits = [(p, ln) for (p, ln) in hits if ln not in SR_OK]
check(not hits, "no trace in the new add-on code: no SetBinding / SaveBindings / macros / CVars / bar writes / Blizzard window code",
      hits[:3])
check(not any('SetOverrideBindingClick' in ln and 'hdr' not in ln and 'owner' not in ln for ln in code),
      "every new key binding is an override on our own owner frame")

# ================================================================ m100: the experimental switch (the user, 2026-11-07: "Make the party
# mode an experimental toggle that is off by default")
WX = world(ok=False)
WX.execute('NOW = NOW + 2; tick(0.1)')
sel = WX.eval('BIND["PADBACK"]')
check(sel != 'CLICK WowCompanionPartyToggle:LeftButton' and WX.eval('WowCompanion.partyOk') is None,
      "m100: switch never set (off by default): Select is NOT Party mode's toggle", sel)
check(party_on_all(WX) == [None] * 9, "m100: switch off: Party mode off")
WX.execute('WowCompanion.partyOk = true; WowCompanion.partyAllow(true); NOW = NOW + 2; tick(0.1)')
sel = WX.eval('BIND["PADBACK"]')
check(sel == 'CLICK WowCompanionPartyToggle:LeftButton', "m100: switch on: Select toggles Party mode", sel)
flip(WX, "WowCompanionPartyOn")
check(party_on_all(WX) == [True] * 9, "m100: switch on: Party mode can turn on")
WX.execute('WowCompanion.partyOk = false; WowCompanion.partyAllow(false)')
check(party_on_all(WX) == [None] * 9, "m100: switch turned off out of a fight: Party mode ends at once", party_on_all(WX))
WX.execute('NOW = NOW + 2; tick(0.1)')
check(WX.eval('BIND["PADBACK"]') != 'CLICK WowCompanionPartyToggle:LeftButton', "m100: ... and Select is the game's again",
      WX.eval('BIND["PADBACK"]'))
WX.execute('WowCompanion.partyOk = true; WowCompanion.partyAllow(true); NOW = NOW + 2; tick(0.1)')
flip(WX, "WowCompanionPartyOn")
WX.execute('COMBAT = true; WowCompanion.partyOk = false; WowCompanion.partyAllow(false)')
check(party_on_all(WX) == [True] * 9 and WX.eval('#VIOL') == 0, "m100: switched off in a fight: no secure write in the lockdown (still on)",
      list(WX.eval('VIOL').values()))
WX.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); NOW = NOW + 2; tick(0.1)')
check(party_on_all(WX) == [None] * 9, "m100: ... it ends at the fight's end", party_on_all(WX))

# ================================================================ m104: the LB party-target map, read from the game's own bindings
# (the user, 2026-11-08: "Not sure: read it from the game")
WL = world(pre='CVARS = { GamePadEmulateCtrl = "PADLSHOULDER", GamePadEmulateShift = "PADLTRIGGER" }; '
               'function GetCVar(n) return CVARS[n] end; '
               'LBKEYS = { TARGETSELF = { "CTRL-PAD1" }, TARGETPARTYMEMBER1 = { "F2", "CTRL-PADDUP" }, TARGETPARTYMEMBER2 = { "CTRL-PADDRIGHT" }, '
               'TARGETPARTYMEMBER3 = { "CTRL-PADDDOWN" }, TARGETPARTYMEMBER4 = { "CTRL-PADDLEFT" } }; '
               'function GetBindingKey(b) local t = LBKEYS[b]; if t then return unpack(t) end; return KEYOF and KEYOF[b] end')
n0 = nsent(WL)
WL.execute('fire("PLAYER_ENTERING_WORLD", false, true); advance(3)')
lm = docs(WL, n0, 'lbmap')
d = lm[-1] if lm else {}
check(bool(lm) and d.get('mod') == 'CTRL' and d.get('self') == 'PAD1' and d.get('p1') == 'PADDUP' and d.get('p2') == 'PADDRIGHT'
      and d.get('p3') == 'PADDDOWN' and d.get('p4') == 'PADDLEFT',
      "m104: lbmap from the game's bindings: LB = Ctrl, A = you, D-pad = party1..4", {k: d.get(k) for k in ('mod', 'self', 'p1', 'p2', 'p3', 'p4')})
check(d.get('keys', {}).get('p1') == ['F2', 'CTRL-PADDUP'], "m104: every key found is in the document (a keyboard key is not LB's)", d.get('keys', {}).get('p1'))


# p34 render aids: CTL_DUMP=<dir> writes the documents the app's ControllerShotsTest draws (never committed fixtures)
def ctl_dump(name, L, typ):
    d = os.environ.get("CTL_DUMP")
    if not d:
        return
    r = raw(L, 0, typ)
    if r:
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, name + ".json"), 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(r[-1])

# ================================================================ p34: the DS tab folded into WoW Defaults; the chooser's DS tab
# (the user, 2026-11-10: "move the DS wow toolbar check box to the Wow defaults tab ... add a 4th skill assignment category where we can
# add the DS toolbar buttons to any button slot")
WT = world(pre='BOOK = { 403, 331, 8004, 324, 131, 546 }')
WT.execute('''
local ctl = WowCompanion._spellsBook.ctl
ctl.croom, ctl.ctrlTab, ctl.onTabs = true, true, true
WowCompanion.padTabCtl.enter("tabs")
function ptab(col) WowCompanion.pad.rows.padtab.handler(nil, col); NOW = NOW + 0.05 end
function pp(key) return WowCompanion.padTabCtl.press(key) end
function want() return WowCompanion.padMap.dpadState().want end
''')
c = ctlDoc(WT)
check(c['view'] == 'normal' and c['focus'] == 'tabs', "p34: the room opens on WoW Defaults, the cursor on the tabs", c)
WT.execute('pp("PADDRIGHT")')
check(ctlDoc(WT)['view'] == 'combat' and ctlDoc(WT)['tcur'] == 3, "p34: Right on the tab row steps over the DS tab (In combat, tab 3)", ctlDoc(WT))
WT.execute('pp("PADLSHOULDER")')
check(ctlDoc(WT)['view'] == 'normal', "p34: LB from In combat: WoW Defaults", ctlDoc(WT)['view'])
WT.execute('pp("PADRSHOULDER")')
check(ctlDoc(WT)['view'] == 'combat', "p34: RB from WoW Defaults: In combat", ctlDoc(WT)['view'])
WT.execute('ptab(1)')
check(ctlDoc(WT)['view'] == 'normal', "p34: padtab 1 (the old DS tab's column) opens WoW Defaults", ctlDoc(WT)['view'])
WT.execute('ptab(0); pp("PADDUP")')
WT.execute('pp("PADDUP"); pp("PADDUP"); pp("PADDUP"); pp("PADDUP")')
check(ctlDoc(WT)['focus'] == 'tabs', "p34: Up to the tab row", ctlDoc(WT)['focus'])
WT.execute('pp("PADDDOWN")')
c = ctlDoc(WT)
check(c['focus'] == 'switches' and c['sw'] == 1, "p34: Down from the tabs: WoW Defaults' own switch (the DS WoW toolbar)", c)
w0 = WT.eval('want()')
WT.execute('pp("PAD1")')
check(WT.eval('want()') == (not w0), "p34: A on it flips the DS WoW toolbar", (w0, WT.eval('want()')))
if WT.eval('want()') is not True:
    WT.execute('pp("PAD1")')
check(WT.eval('want()') is True, "p34: the toolbar on", WT.eval('want()'))
WT.execute('pp("PADDDOWN"); pp("PADDDOWN"); pp("PADDDOWN")')
c = ctlDoc(WT)
check(c['focus'] == 'buttons', "p34: down to the buttons", c['focus'])
# to the D-pad's Left (position 1), the bare set
for _ in range(6):
    WT.execute('pp("PADDLEFT")')
c = ctlDoc(WT)
check(c['pos'] == 1 and c['shown'] == 1, "p34: the bare D-pad Left", c)
WT.execute('WowCompanion.padMap.emit(true)')
ctl_dump('padmap_on', WT, 'padmap'); ctl_dump('ctl_dleft', WT, 'padtabctl')
WT.execute('pp("PAD1")')
c = ctlDoc(WT)
check(c['note'] == 'dpad-bar' and c.get('chooser') is None, "p34: A on the toolbar's own button: refused (dpad-bar), no chooser", c)
WT.execute('pp("PAD3")')
check(ctlDoc(WT)['note'] == 'dpad-bar' and ctlDoc(WT).get('carry') is None, "p34: X there: refused too", ctlDoc(WT))
# p36 (the user 2026-11-10: "i should still be able to assign combat keys with it on"): In combat's bare D-pad is the player's
WT.execute('pp("PADRSHOULDER")')
c = ctlDoc(WT)
check(c['view'] == 'combat' and c['pos'] == 1 and c['shown'] == 1, "p36: RB to In combat, still the bare D-pad Left", c)
WT.execute('pp("PAD1")')
c = ctlDoc(WT)
check(c.get('note') != 'dpad-bar' and c.get('chooser'), "p36: toolbar on, In combat: A on the D-pad opens the chooser", c)
WT.execute('pp("PAD2")')
check(not ctlDoc(WT).get('chooser'), "p36: B closes it", ctlDoc(WT).get('chooser'))
WT.execute('pp("PADLSHOULDER")')
c = ctlDoc(WT)
check(c['view'] == 'normal' and c['pos'] == 1 and c['shown'] == 1, "p36: LB back to WoW Defaults' D-pad Left", c)
# the toolbar off: the button is the player's again, and the chooser has the DS tab
WT.execute('WowCompanion.padMap.dpadBar(false); pp("PAD1")')
c = ctlDoc(WT)
check(WT.eval('want()') is False and c.get('chooser') and c['chooser']['list'] == 'spells', "p34: toolbar off: A opens the chooser", c.get('chooser'))


def walk(L):
    ids = []
    for _ in range(80):
        r = ctlDoc(L)['chooser']
        if ids and ids[-1] == r['id']:
            break
        ids.append(r['id'])
        L.execute('pp("PADDDOWN")')
    L.execute('for i = 1, 80 do pp("PADDUP") end; pp("PADDDOWN")')
    return ids


WT.execute('pp("PADDRIGHT"); pp("PADDRIGHT"); pp("PADDRIGHT")')
check(ctlDoc(WT)['chooser']['list'] == 'extra', "p34: the 4th chooser tab is Extra", ctlDoc(WT)['chooser'])
extra = walk(WT)
check(extra and not any(isinstance(i, str) and i.startswith('DS') for i in extra), "p34: Extra holds no DS Forever command now", extra)
WT.execute('pp("PADDRIGHT")')
check(ctlDoc(WT)['chooser']['list'] == 'dstool', "p34: Right again: the DS tab (dstool)", ctlDoc(WT)['chooser'])
WT.execute('WowCompanion.padMap.emit(true)')
ctl_dump('padmap_off', WT, 'padmap'); ctl_dump('ctl_dstool', WT, 'padtabctl')
ds = walk(WT)
check({'DSCHAT', 'DSRADIAL', 'DSMAP', 'DSHOME', 'DSSRADIAL'} <= set(ds) and all(isinstance(i, str) and i.startswith('DS') for i in ds),
      "p34: the DS tab: Chat, Radial, Map, Home bar, Spell Radial (DS Forever's own only)", ds)
check(not any(i.startswith('DSBAR') for i in ds), "p35: the room bar's Left / Right / Open are in no picker", ds)
WT.execute('pp("PADDRIGHT")')
check(ctlDoc(WT)['chooser']['list'] == 'dstool', "p34: the DS tab is the last (no wrap)", ctlDoc(WT)['chooser'])
WT.execute('for i = 1, 80 do pp("PADDUP") end; pp("PADDDOWN")')
while ctlDoc(WT)['chooser']['id'] != 'DSMAP':
    WT.execute('pp("PADDDOWN")')
# (this world has no game bars to write: the put itself is what PadTabCtl hands PadMap)
WT.execute('PUTS = {}; local pm = WowCompanion.padMap; local f = pm.putCommand; '
           'pm.putCommand = function(pg, st, ps, k) PUTS[#PUTS + 1] = st .. ":" .. ps .. ":" .. k; return f(pg, st, ps, k) end')
WT.execute('pp("PAD1")')
for _ in range(3):
    if ctlDoc(WT).get('box'):
        WT.execute('pp("PAD1")')
puts = [WT.eval('PUTS[%d]' % (i + 1)) for i in range(int(WT.eval('#PUTS')))]
check(puts == ['1:1:DSMAP'], "p34: Open Map from the DS tab is put on the bare D-pad Left (PadMap.putCommand)", puts)
check(WT.eval('#SPY') == 0 and WT.eval('#VIOL') == 0, "p34: no spied call, no lockdown breach")

# ================================================================ p39: the In combat tab's chooser has all five tabs (the user, 2026-11-11,
# on 1428: "The in combat tab of the controller bindings is missing all 5 pages of options, it only shows the first 3")
WT.execute('pp("PADRSHOULDER")')
c = ctlDoc(WT)
check(c['view'] == 'combat' and c['pos'] == 1 and c['shown'] == 1 and WT.eval('want()') is False, "p39: In combat, the bare D-pad Left, toolbar off", c)
WT.execute('pp("PAD1")')
check(ctlDoc(WT).get('chooser') and ctlDoc(WT)['chooser']['list'] == 'spells', "p39: A opens In combat's chooser on Spells", ctlDoc(WT).get('chooser'))
seen = []
for _ in range(6):
    seen.append(ctlDoc(WT)['chooser']['list'])
    WT.execute('pp("PADDRIGHT")')
seen = list(dict.fromkeys(seen))
check(seen == ['spells', 'general', 'professions', 'extra', 'dstool'], "p39: In combat's chooser: all five tabs (Extra and DS too)", seen)
ds = walk(WT)
check({'DSCHAT', 'DSMAP'} <= set(ds), "p39: In combat's DS tab lists DS Forever's commands", ds)
WT.execute('pp("PAD2")')
# a fight spell on the button first; an In combat DS entry put there replaces it (the button does the command in fights too)
# (this world has no game bars: the button reads as one with a fight spell over it, PadMap m43's view)
WT.execute('local pm = WowCompanion.padMap; SLOTAT0 = pm.slotAt; local f = SLOTAT0; pm.slotAt = function(pg, st, ps) '
           'if st == 1 and ps == 1 then return { kind = "combat", c = { id = 403, n = "Lightning Bolt", i = 1 }, ov = true } end '
           'return f(pg, st, ps) end')
WT.execute('PUTS = {}; CLR = {}; FIGHTS = {}; local pm = WowCompanion.padMap; local g = pm.clearCombat; '        # (the p34 putCommand spy stays)
           'pm.clearCombat = function(pg, st, ps) CLR[#CLR + 1] = st .. ":" .. ps; return g(pg, st, ps) end; '
           'local h = pm.putFight; pm.putFight = function(pg, st, ps, k, v, fm) FIGHTS[#FIGHTS + 1] = st .. ":" .. ps .. ":" .. k .. ":" .. tostring(v) '
           '.. (fm and (":" .. fm) or ""); return h(pg, st, ps, k, v, fm) end')
WT.execute('pp("PAD1"); for i = 1, 4 do pp("PADDRIGHT") end')
check(ctlDoc(WT)['chooser']['list'] == 'dstool', "p39: In combat on a fight-spell button: the DS tab is there too", ctlDoc(WT)['chooser'])
WT.execute('WowCompanion.padMap.emit(true)')
ctl_dump('padmap_combat', WT, 'padmap'); ctl_dump('ctl_combat_dstool', WT, 'padtabctl')
WT.execute('for i = 1, 80 do pp("PADDUP") end; pp("PADDDOWN")')
for _ in range(40):
    if ctlDoc(WT)['chooser']['id'] == 'DSMAP':
        break
    WT.execute('pp("PADDDOWN")')
WT.execute('pp("PAD1")')
b = ctlDoc(WT).get('box')
check(b and b['kind'] == 'replace' and b.get('what') == 'DSMAP' and int(WT.eval('#PUTS')) == 0 and int(WT.eval('#FIGHTS')) == 0,
      "p41: Open Map on In combat's fight-spell button: Replace (the fight layer's spell) asks first, nothing changed yet", b)
WT.execute('pp("PAD1")')
puts = [WT.eval('PUTS[%d]' % (i + 1)) for i in range(int(WT.eval('#PUTS')))]
clr = [WT.eval('CLR[%d]' % (i + 1)) for i in range(int(WT.eval('#CLR')))]
fights = [WT.eval('FIGHTS[%d]' % (i + 1)) for i in range(int(WT.eval('#FIGHTS')))]
check(fights == ['1:1:command:DSMAP'] and puts == [] and clr == [] and not ctlDoc(WT).get('box'),
      "p41: Replace's ONE yes: Open Map goes on the FIGHT LAYER only (putFight; no putCommand on the button, no second box)", (fights, puts, clr))
# p41: an EMPTY fight layer on the In combat tab: a DS entry goes straight on (the fight layer only: nothing out of combat changes)
WT.execute('local pm = WowCompanion.padMap; pm.slotAt = function(pg, st, ps) '
           'if st == 1 and ps == 1 then return { kind = "none" } end return SLOTAT0(pg, st, ps) end')
WT.execute('PUTS = {}; CLR = {}; FIGHTS = {}; pp("PAD1"); for i = 1, 4 do pp("PADDRIGHT") end')
WT.execute('for i = 1, 80 do pp("PADDUP") end; pp("PADDDOWN")')
for _ in range(40):
    if ctlDoc(WT)['chooser']['id'] == 'DSMAP':
        break
    WT.execute('pp("PADDDOWN")')
WT.execute('pp("PAD1")')
b = ctlDoc(WT).get('box')
fights = [WT.eval('FIGHTS[%d]' % (i + 1)) for i in range(int(WT.eval('#FIGHTS')))]
c = ctlDoc(WT)
check(b is None and fights == ['1:1:command:DSMAP'] and int(WT.eval('#PUTS')) == 0 and int(WT.eval('#CLR')) == 0
      and not c.get('chooser'), "p41: Open Map on an empty In combat button: straight onto the fight layer, no box, the chooser closes", (b, fights))
WT.execute('WowCompanion.padMap.slotAt = SLOTAT0')
# ================================================================ p43 (the user, 2026-11-12, on 1429: "In combat still does not show all 5
# tabs", then the pick "All 5 on every button"): EVERY In combat button lists all five tabs - the base X / Y / A, a form's own set, the
# bare D-pad with and without the DS WoW toolbar, ZL / ZR / ZL + ZR (B stays the game's Back: no chooser)
def tabs_here(L):
    L.execute('pp("PAD1")')
    for _ in range(3):
        if ctlDoc(L).get('box'):
            L.execute('pp("PAD2")')
    seen = []
    for _ in range(6):
        ch = ctlDoc(L).get('chooser')
        if not ch:
            break
        seen.append(ch['list'])
        L.execute('pp("PADDRIGHT")')
    if ctlDoc(L).get('chooser'):
        L.execute('pp("PAD2")')
    return list(dict.fromkeys(seen))


def at(L, set_, pos, form=0):
    L.execute('local t = WowCompanion.padTabCtl.state(); t.set = %d; t.pos = %d; t.form = %d; t.focus = "buttons"; '
              't.chooser = nil; t.box = nil; t.carry = nil' % (set_, pos, form))


FIVE = ['spells', 'general', 'professions', 'extra', 'dstool']
check(ctlDoc(WT)['view'] == 'combat', "p43: on the In combat tab", ctlDoc(WT)['view'])
bad = []
for tb in (False, True):
    WT.execute('WowCompanion.padMap.dpadBar(%s)' % ('true' if tb else 'false'))
    for st in (1, 2, 3, 4):
        for p in (1, 2, 3, 4, 5, 6, 8):
            at(WT, st, p)
            got = tabs_here(WT)
            if got != FIVE:
                bad.append((tb, st, p, got))
    for p in (1, 2, 3, 4, 5, 6, 8):                         # a form's own set (Cat Form chosen on the form row)
        at(WT, 1, p, 768)
        got = tabs_here(WT)
        if got != FIVE:
            bad.append((tb, 'form', p, got))
WT.execute('WowCompanion.padMap.dpadBar(false)')
check(bad == [], "p43: In combat, every button (sets 1-4, the D-pad and A / X / Y, a form's set, the toolbar on and off) lists all five tabs", bad)
# p43: an Extra / DS entry on base A goes on A's FIGHT LAYER (PadMap.putFight on set 1 position 8); WoW Defaults (the game's Jump) stays
at(WT, 1, 8)
WT.execute('FIGHTS = {}; PUTS = {}; pp("PAD1"); for i = 1, 4 do pp("PADDRIGHT") end')
check(ctlDoc(WT)['chooser']['list'] == 'dstool', "p43: base A's chooser on the DS tab", ctlDoc(WT)['chooser'])
WT.execute('for i = 1, 80 do pp("PADDUP") end; pp("PADDDOWN")')
for _ in range(40):
    if ctlDoc(WT)['chooser']['id'] == 'DSMAP':
        break
    WT.execute('pp("PADDDOWN")')
WT.execute('pp("PAD1")')
if ctlDoc(WT).get('box'):
    WT.execute('pp("PAD1")')
fights = [WT.eval('FIGHTS[%d]' % (i + 1)) for i in range(int(WT.eval('#FIGHTS')))]
fe = WT.eval('(WowCompanionSpellsDB.faceBy or {})[WHO .. "-" .. REALM] and WowCompanionSpellsDB.faceBy[WHO .. "-" .. REALM][8]')
a8 = WT.eval('WowCompanion.padMap.slotAt(1, 1, 8)')
check(fights == ['1:8:command:DSMAP'] and int(WT.eval('#PUTS')) == 0 and fe is not None and fe.key == 'DSMAP' and fe.t == 'command',
      "p43: Open Map from base A's DS tab goes on A's fight layer (putFight, the face store), no putCommand", (fights, dict(fe) if fe else None))
check(a8.kind == 'fixed' and a8.cf is not None and a8.cf.key == 'DSMAP',
      "p43: WoW Defaults stays: A is still the game's own button (fixed); Open Map is only its In combat entry", (a8.kind, a8.n))
WT.execute('WowCompanion.padMap.emit(true)')
at(WT, 1, 8)
WT.execute('pp("PAD1"); pp("PAD2"); pp("PAD1"); for i = 1, 4 do pp("PADDRIGHT") end')
ctl_dump('padmap_combat_a', WT, 'padmap'); ctl_dump('ctl_combat_a', WT, 'padtabctl')
check(ctlDoc(WT)['chooser']['list'] == 'dstool' and ctlDoc(WT)['pos'] == 8, "p43: base A (with its fight-layer command): the DS tab again",
      ctlDoc(WT)['chooser'])
# a binding on base A: the row is there, but refused with the reason (bind-fixed: the game keeps the key)
WT.execute('pp("PADDLEFT")')
check(ctlDoc(WT)['chooser']['list'] == 'extra', "p43: base A's Extra tab", ctlDoc(WT)['chooser'])
for _ in range(60):
    r = ctlDoc(WT)['chooser']
    if r['id'] == 'JUMP':
        break
    WT.execute('pp("PADDDOWN")')
if ctlDoc(WT)['chooser']['id'] == 'JUMP':
    WT.execute('FIGHTS = {}; pp("PAD1")')
    check(ctlDoc(WT).get('note') == 'bind-fixed' and int(WT.eval('#FIGHTS')) == 0, "p43: Jump on base A: refused, bind-fixed (nothing put)",
          ctlDoc(WT).get('note'))
WT.execute('pp("PAD2"); WowCompanion.padMap.clearCombatFace(8)')
# a form's set: a binding refused (bind-form), an Extra entry goes on the form's fight layer (putFight with the form)
at(WT, 1, 5, 768)
WT.execute('FIGHTS = {}; pp("PAD1"); for i = 1, 3 do pp("PADDRIGHT") end')
for _ in range(60):
    if ctlDoc(WT)['chooser']['id'] == 'JUMP':
        break
    WT.execute('pp("PADDDOWN")')
WT.execute('pp("PAD1")')
check(ctlDoc(WT).get('note') == 'bind-form' and int(WT.eval('#FIGHTS')) == 0, "p43: Jump on a form's X: refused, bind-form", ctlDoc(WT).get('note'))
WT.execute('pp("PAD2")')
at(WT, 1, 1)
# WoW Defaults unchanged: five on a plain button
WT.execute('pp("PADLSHOULDER")')
for _ in range(12):
    if ctlDoc(WT)['pos'] == 2:
        break
    WT.execute('pp("PADDLEFT")')
WT.execute('pp("PAD1")')
for _ in range(3):
    if ctlDoc(WT).get('box'):
        WT.execute('pp("PAD2")')
WT.execute('for i = 1, 6 do pp("PADDRIGHT") end')
c = ctlDoc(WT)
check(c['view'] == 'normal' and c.get('chooser') and c['chooser']['list'] == 'dstool', "p39: WoW Defaults keeps its five tabs", (c['view'], c['pos'], c.get('chooser')))
WT.execute('pp("PAD2")')
check(WT.eval('#SPY') == 0 and WT.eval('#VIOL') == 0, "p39: no spied call, no lockdown breach")

# ================================================================ m132 PARTY MODE IN A FIGHT, THE FIGHT FIRST (the user, 2026-11-11, a healer:
# "Party mode needs to be usable in combat for healers. Locking it is retarded"). Every world here ENTERS THE FIGHT FIRST (the lockdown
# on: any insecure secure-frame / binding / attribute write is a VIOL) and only then turns Party mode on, picks, moves, casts, Touches,
# changes the roster and the pet - all through the keys bound before the lockdown, the party row's secure cells and the state drivers.
def cell(L, col):
    a0 = nact(L)
    L.execute('local c = WowCompanion.pad.rows.party.cells[%d]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false); NOW = NOW + 0.3' % col)
    return acts(L, a0)


def pressk(L, key, lt=False, rt=False):
    L.execute('trig(%s, %s)' % ('true' if lt else 'false', 'true' if rt else 'false'))
    a0 = nact(L)
    L.execute('tapKey("%s"); NOW = NOW + 0.3' % key)
    L.execute('trig(false, false)')
    return acts(L, a0)


def viol(L):
    return int(L.eval('#VIOL')), int(L.eval('#SNIPERR')), int(L.eval('#SPY'))


WF = world(pre='BOOK = { 403, 331, 324, 8004 }; WowCompanionSpellsDB.binds = { ["Korvash-Realm"] = { ["2:8"] = "JUMP", ["1:1"] = "TOGGLEAUTORUN" } }')
WF.eval('party')(WF.table_from([WF.table_from(m) for m in MEMBERS[:2]]))
WF.execute('fire("GROUP_ROSTER_UPDATE"); advance(1)')
WF.eval('WowCompanion.padMap.party.put')("touch", 8004)
setps(WF, a="Healing Wave", zla="Cure Poison")
WF.execute('rawget(_G, "WowCompanionPartyPad").a["wc-py"] = nil; advance(1)')
FIGHT_KEYS = router_on(WF)
FIGHT_KEYS["PADBACK"] = "CLICK WowCompanionPartyToggle:LeftButton"
check(WF.eval('PADA("wc-party")') is None and keys(WF).get("SHIFT-PAD1") == "JUMP", "m132 fight-first: out of combat, grouped, Party mode off, the chords bound",
      keys(WF))
WF.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true')
check(keys(WF) == FIGHT_KEYS, "m132 fight-first: the fight starts (grouped, off): all 8 router keys + Select carried; no m34 chord over the party layers",
      keys(WF))
pressk(WF, "PADBACK")
check(party_on_all(WF) == [True] * 9 and viol(WF) == (0, 0, 0), "m132 fight-first: Select IN the fight turns Party mode on (secure only)",
      (party_on_all(WF), viol(WF)))
n0 = nsent(WF)
pressk(WF, "PADDDOWN")
pb = docs(WF, n0, 'partybox')
check(WF.eval('PADA("wc-box")') == 2 and WF.eval('PADA("wc-u")') == "party1" and pb and pb[-1]['b']['k'] == 'move' and pb[-1]['b']['fight'] is True,
      "m132 fight-first: D-pad Down picks party1 (box 2), partybox k move, fight true", pb and pb[-1]['b'])
got = pressk(WF, "PAD1")
check(got == [("spell", "Healing Wave", "party1")], "m132 fight-first: A casts its spell on the picked member", got)
got = pressk(WF, "PAD1", lt=True)
check(got == [("spell", "Cure Poison", "party1")], "m132 fight-first: ZL + A casts the ZL layer's spell (no Jump chord in the way)", got)
got = cell(WF, 1)
check(got == [("spell", "Lesser Healing Wave", "party1")], "m132 fight-first: a tap on the picked plate = Touch (its spell on party1)", got)
got = cell(WF, 2)
check(got == [("target", None, "party2")] and WF.eval('PADA("wc-box")') == 3 and WF.eval('PADA("wc-u")') == "party2",
      "m132 fight-first: a tap on party2's plate picks it (m134: and targets it)",
      (got, WF.eval('PADA("wc-box")')))
got = pressk(WF, "PAD4")
check(got == [("target", None, "party2")] and WF.eval('PADA("wc-pmenu")') is None, "m132 fight-first: Y with nothing armed = Target party2 (no menu)", got)
got = pressk(WF, "PADDUP")
check(WF.eval('PADA("wc-box")') == 2 and WF.eval('PADA("wc-k")') == "move" and got == [("target", None, "party1")],
      "m132 fight-first: D-pad Up moves back (no menu held the press; m134: party1 targeted)",
      WF.eval('PADA("wc-box")'))
# the roster changes in the fight: party3 joins (the driver keeps the pick), the Right in the shared row reaches it, the pet comes out
WF.execute('UNITS.party3 = { name = "Doran", cls = "ROGUE", alive = true, role = "DAMAGER", pt = "ENERGY", guid = "Player-1-Doran" }; '
           'fire("GROUP_ROSTER_UPDATE"); driveStates()')
check(WF.eval('PADA("wc-box")') == 2 and WF.eval('PADA("wc-u")') == "party1" and WF.eval('PADA("wc-cnt")') == 4 and viol(WF) == (0, 0, 0),
      "m132 fight-first: party3 joins in the fight: the pick stays on party1 (cnt 4)", (WF.eval('PADA("wc-box")'), WF.eval('PADA("wc-u")'), viol(WF)))
pressk(WF, "PADDDOWN")
pressk(WF, "PADDRIGHT")
check(WF.eval('PADA("wc-box")') == 4 and WF.eval('PADA("wc-u")') == "party3", "m132 fight-first: Down then Right (the shared bottom row): party3",
      (WF.eval('PADA("wc-box")'), WF.eval('PADA("wc-u")')))
WF.execute('UNITS.pet = { name = "Wolf", alive = true }; fire("UNIT_PET", "player"); driveStates()')
check(WF.eval('PADA("wc-box")') == 5 and WF.eval('PADA("wc-u")') == "party3" and WF.eval('PADA("wc-cnt")') == 5,
      "m132 fight-first: the pet comes out in the fight: still party3 (box 5, cnt 5)", (WF.eval('PADA("wc-box")'), WF.eval('PADA("wc-u")')))
got = pressk(WF, "PAD1")
check(got == [("spell", "Healing Wave", "party3")], "m132 fight-first: A on party3 after the roster and pet changes", got)
WF.execute('local p = rawget(_G, "WowCompanionPartyPad"); p.a["wc-box"] = 1; p.a["wc-u"] = "player"')
got = pressk(WF, "PADDRIGHT")
check(WF.eval('PADA("wc-u")') == "pet" and got == [("target", None, "pet")], "m134 fight-first: Right from you = the pet (you | pet share the first row), targeted", got)
got = pressk(WF, "PAD1")
check(got == [("spell", "Healing Wave", "pet")], "m132 fight-first: A heals the pet", got)
# documents in the fight: the party document says on, no aside, live (the app draws the pick from it)
n0 = nsent(WF)
WF.execute('fire("UNIT_LEVEL", "party1"); advance(1)')
pd = docs(WF, n0, 'party')
check(pd and pd[-1]['p']['on'] is True and 'aside' not in pd[-1]['p'] and pd[-1]['p']['fight'] is True and pd[-1]['p']['sel'] == 'bound'
      and len(pd[-1]['p']['m']) == 5, "m132 fight-first: the party document in the fight: on, no aside, fight, sel bound, 5 entries", pd and pd[-1]['p'])
# a room / the bar cursor / a game window take the controller in the fight: only suspended, never off (in or after the fight)
WF.execute('HOLDER = "pad:bar"; tick(1); HOLDER = nil; LootFrame = { IsShown = function() return true end }; tick(1); LootFrame = nil; tick(1)')
check(party_on_all(WF) == [True] * 9 and viol(WF) == (0, 0, 0), "m132 fight-first: the bar cursor and a loot window in the fight: Party mode stays on",
      party_on_all(WF))
# off and on again in the same fight: Select, then the bottom tab's cells (col 11 off, col 10 on), B
pressk(WF, "PADBACK")
check(party_on_all(WF) == [None] * 9, "m132 fight-first: Select again: off", party_on_all(WF))
got = pressk(WF, "PAD1")
check(all(a[2] is None for a in got), "m132 fight-first: off, A is the fight router's own again (no party cast)", got)
cell(WF, 10)
check(party_on_all(WF) == [True] * 9, "m132 fight-first: the tab's ON (col 10) in the fight", party_on_all(WF))
cell(WF, 11)
check(party_on_all(WF) == [None] * 9, "m132 fight-first: the tab's OFF (col 11) in the fight", party_on_all(WF))
pressk(WF, "PADBACK")
pressk(WF, "PAD2")
check(party_on_all(WF) == [None] * 9 and WF.eval('PADA("wc-k")') == "off", "m132 fight-first: Select on, B off (the pad's own)", WF.eval('PADA("wc-k")'))
pressk(WF, "PADBACK")
# what the game forbids stays refused, and says why
n0 = nsent(WF)
WF.eval('WowCompanion.padMap.party.put')("a", 331)
ack = docs(WF, n0, 'partyack')
check(ack and ack[-1]['ok'] is False and ack[-1]['why'] == 'combat', "m132 fight-first: a Controller-room edit in the fight: refused, why combat", ack)
check(viol(WF) == (0, 0, 0), "m132 fight-first: the whole fight: no lockdown breach, no snippet error, no spied call", viol(WF))
WF.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1); NOW = NOW + 2; tick(0.1)')
check(party_on_all(WF) == [True] * 9 and keys(WF) == FIGHT_KEYS, "m132 fight-first: the fight ends: Party mode still on, its keys and Select bound",
      keys(WF))
pressk(WF, "PADBACK")
WF.execute('UNITS.pet = nil; UNITS.party3 = nil; fire("GROUP_ROSTER_UPDATE"); driveStates(); advance(1); NOW = NOW + 2; tick(0.1)')

# a GAME WINDOW OPEN AT THE PULL (a loot roll left showing): the router no longer steps aside, Select and the keys are carried
for win in ("GroupLootFrame1", "LootFrame", "StaticPopup1"):
    WF.execute('%s = { IsShown = function() return true end }; NOW = NOW + 2; tick(0.1)' % win)
    check(not any(v.startswith("CLICK WowCompanion") for v in keys(WF).values()), "m132 window-at-pull: %s out of combat: the router steps aside" % win,
          keys(WF))
    WF.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true')
    check(keys(WF) == FIGHT_KEYS, "m132 window-at-pull: the fight starts with %s showing: all 8 keys + Select carried" % win, keys(WF))
    pressk(WF, "PADBACK")
    pressk(WF, "PADDDOWN")
    got = pressk(WF, "PAD1")
    check(party_on_all(WF) == [True] * 9 and got and got[-1][1] == "Healing Wave" and viol(WF) == (0, 0, 0),
          "m132 window-at-pull (%s): Select on, Down, A heals the pick" % win, (got, viol(WF)))
    WF.execute('%s = nil; COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1); NOW = NOW + 2; tick(0.1)' % win)
    check(party_on_all(WF) == [True] * 9, "m132 window-at-pull (%s): still on after the fight" % win, party_on_all(WF))
    pressk(WF, "PADBACK")
# Party mode ON with a prompt showing (suspended) when the fight starts: never switched off; live for the fight
pressk(WF, "PADBACK")
WF.execute('StaticPopup1 = { IsShown = function() return true end }; NOW = NOW + 2; tick(0.1)')
check(WF.eval('PADA("wc-party")') is True and not any(v.startswith("CLICK WowCompanion") for v in keys(WF).values()),
      "m132: on, a popup out of combat: suspended (still on)", keys(WF))
WF.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true')
got = pressk(WF, "PAD1")
check(party_on_all(WF) == [True] * 9 and keys(WF) == FIGHT_KEYS and got and got[-1][1] == "Healing Wave",
      "m132: ... the fight starts with the popup: Party mode on and live (keys carried, A casts)", (keys(WF), got))
WF.execute('StaticPopup1 = nil; COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1); NOW = NOW + 2; tick(0.1)')
pressk(WF, "PADBACK")
check(viol(WF) == (0, 0, 0), "m132 window-at-pull: no lockdown breach, no snippet error, no spied call", viol(WF))

# SOLO with a pet: the fight first, Select still the toggle's; the pet picked by the D-pad and healed
WS = world()
WS.execute('UNITS.pet = { name = "Wolf", alive = true }; fire("UNIT_PET", "player"); driveStates(); advance(1)')
setps(WS, a="Healing Wave")
WS.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true')
check(keys(WS).get("PADBACK") == "CLICK WowCompanionPartyToggle:LeftButton", "m132 solo: the fight starts solo (a pet out): Select carried", keys(WS))
pressk(WS, "PADBACK")
got = pressk(WS, "PADDDOWN")
check(got == [] and WS.eval('PADA("wc-u")') == "player", "m134 solo: Down from you with only the pet: no row under you, nothing", got)
pressk(WS, "PADDRIGHT")
got = pressk(WS, "PAD1")
check(WS.eval('PADA("wc-u")') == "pet" and got == [("spell", "Healing Wave", "pet")] and viol(WS) == (0, 0, 0),
      "m134 solo: Select on, Right = the pet, A heals it (secure only)", (got, viol(WS)))
WS.execute('UNITS.pet = nil; driveStates()')
check(WS.eval('PADA("wc-u")') == "player" and WS.eval('PADA("wc-cnt")') == 1, "m132 solo: the pet dies in the fight: the pick clamps to you",
      WS.eval('PADA("wc-u")'))
WS.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
# m133 (review): solo with Party mode off (no pet, ungrouped): a game window at the pull keeps its own navigation (aside, as before m132)
WO = world()
WO.execute('GameMenuFrame = { IsShown = function() return true end }; NOW = NOW + 2; tick(0.1); fire("PLAYER_REGEN_DISABLED"); COMBAT = true')
check(not any(v.startswith("CLICK WowCompanion") for v in keys(WO).values()),
      "m133: solo, no pet, Party mode off: a fight's start with the game menu open stays aside (its controller navigation kept)", keys(WO))
WO.execute('GameMenuFrame = nil; COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
# m133 (review): solo with a pet, a bare D-pad binding of the player's own: the fight's start carries the D-pad (no chord), so the pick
# reaches the pet after Party mode is turned on in the fight; a window at the pull is ignored as when grouped
WP = world(pre='WowCompanionSpellsDB.binds = { ["Korvash-Realm"] = { ["2:8"] = "JUMP", ["1:4"] = "TOGGLEAUTORUN" } }')
WP.execute('UNITS.pet = { name = "Wolf", alive = true }; fire("UNIT_PET", "player"); driveStates(); advance(1)')
setps(WP, a="Healing Wave")
WP.execute('LootFrame = { IsShown = function() return true end }; NOW = NOW + 2; tick(0.1)')
WP.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true')
k = keys(WP)
check(k.get("PADBACK") == "CLICK WowCompanionPartyToggle:LeftButton" and "SHIFT-PAD1" not in k and k.get("PADDDOWN", "").startswith("CLICK WowCompanion"),
      "m133 solo + pet: the fight's start (a loot window open): Select and the D-pad carried, no m34 chord (SHIFT-PAD1 / PADDDOWN)", k)
pressk(WP, "PADBACK")
pressk(WP, "PADDRIGHT")
got = pressk(WP, "PAD1")
check(WP.eval('PADA("wc-u")') == "pet" and got == [("spell", "Healing Wave", "pet")] and viol(WP) == (0, 0, 0),
      "m133 solo + pet: Party mode on in the fight, Right = the pet (m134), A heals it", (got, viol(WP)))
WP.execute('LootFrame = nil; COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
# the experimental switch off: today's rules (Select the game's, a window at the pull keeps the router aside)
WN = world(ok=False)
WN.execute('LootFrame = { IsShown = function() return true end }; NOW = NOW + 2; tick(0.1); fire("PLAYER_REGEN_DISABLED"); COMBAT = true')
# m134 (combat_nav_design Step 2; the user's decision 1, 2026-11-11: the game's windows at the pull "should be built on the bottom
# screen"; only the game menu keeps the game's pad): every key carried, Select still the game's (Party mode not allowed)
check("PADBACK" not in keys(WN) and keys(WN).get("PADDDOWN", "").startswith("CLICK WowCompanion"),
      "m134: Party mode NOT allowed: a fight's start with a loot window carries the keys (the bottom screen's), Select the game's", keys(WN))
WN.execute('LootFrame = nil; COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
check(('function' not in SRC["padmap"][SRC["padmap"].index('PM.PARTYPAD = [=['):SRC["padmap"].index(']=]', SRC["padmap"].index('PM.PARTYPAD = [=['))]),
      "m132: the PARTYPAD snippet holds no f-u-n-c-t-i-o-n (the game's secure checker refuses it)")

# ================================================================ m124: the In-combat spells follow the rank that casts
WR = world()
WR.execute('''
local db = WowCompanionSpellsDB.fightBy or {}; WowCompanionSpellsDB.fightBy = db
local k = WHO .. "-" .. REALM
db[k] = { [61] = { id = 1978, n = "Serpent Sting", i = 1, page = 1, set = 1, pos = 1 }, [62] = { id = 403, n = "Lightning Bolt", i = 2, page = 1, set = 1, pos = 2 } }
C_Spell.GetSpellInfo = function(x)
    if x == "Serpent Sting" then return { spellID = 13549, name = "Serpent Sting", iconID = 132204 } end
    if x == "Lightning Bolt" then return { spellID = 403, name = "Lightning Bolt", iconID = 136048 } end
end
fire("SPELLS_CHANGED"); advance(1)   -- m144: the sync runs a moment after SPELLS_CHANGED (the prompt's news first)
''')
k = WR.eval('WHO .. "-" .. REALM')
ss = WR.eval('WowCompanionSpellsDB.fightBy[WHO .. "-" .. REALM][61].id')
lb = WR.eval('WowCompanionSpellsDB.fightBy[WHO .. "-" .. REALM][62].id')
check(ss == 13549 and lb == 403, "m124: SPELLS_CHANGED moves Serpent Sting from Rank 1 (1978) to the rank its name casts (13549); Lightning Bolt stays", (ss, lb))
check(WR.eval('WowCompanionSpellsDB.fightBy[WHO .. "-" .. REALM][61].i') == 132204, "m124: and its icon", WR.eval('WowCompanionSpellsDB.fightBy[WHO .. "-" .. REALM][61].i'))

# ================================================================ m125: a run-out GCD is gone; the next press sends a fresh one
WG = world()
WG.execute('''
local k = WHO .. "-" .. REALM
WowCompanionSpellsDB.faceBy = { [k] = { [5] = { id = 3044, n = "Arcane Shot", i = 1 } } }
CDINFO = { isActive = true, isOnGCD = true }
C_Spell.GetSpellCooldown = function(x) return CDINFO end
fire("SPELL_UPDATE_COOLDOWN")
''')
d1 = [d for d in docs(WG, 0, 'facecd') if d.get('k')]
check(len(d1) >= 1 and d1[-1]['k'].get('5', {}).get('g') is True, "m125: the first GCD is sent on X", d1[-1:] )
n1 = int(WG.eval('#SENT'))
WG.execute('advance(2); fire("SPELL_UPDATE_COOLDOWN")')      # the game never said "not active" in between
d2 = [d for d in docs(WG, n1, 'facecd') if d.get('k')]
check(len(d2) >= 1 and d2[-1]['k'].get('5', {}).get('g') is True and d2[-1]['k']['5'].get('l', 0) > 1.0,
      "m125: 2 s later (no 'not active' read), the next GCD is sent fresh", d2[-1:])

print('ALL PASS (%d checks)' % passes if fails == 0 else '%d FAILED (of %d)' % (fails, fails + passes))
sys.exit(1 if fails else 0)
