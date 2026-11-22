"""B: PET FOLLOW (PadMap m165, Book b66), off-device: the REAL add-on files (Safety, TapPad, Map, Quests, Hub, Interact + Dialogs, Book,
PadMap, PadTabCtl) under tools/wowstub.py's game with STRICT laid over it (the lockdown, the press model, the restricted snippets).

    python -B tools/test_b_pet_follow.py

The user, 2026-11-16: "I want a pet class specific option where pressing B in the out of combat home menu basically stops the pet
attacking". The picks: B does the game's own Pet Follow (/petfollow: it stops attacking, comes back, keeps its stance); a Settings row on
the Controller tab, on by default for a pet class, hidden for the others, per character. WHAT B DID THERE BEFORE (blizzard_gamepadactionbars
SetupNonBindableBack): Forever's own Back - stop casting, else stop targeting, else clear the target - and that stays.

Proves: (0) the versions; (1) before the app's word B is exactly as before (the game's own binding); (2) the word on: B is the router's,
one press = /petfollow THEN Forever's Back (its down and up edges as before); (3) the word off: B is the game's again; (4) no pet / a dead
pet / a pet that dies before the check: no Pet Follow; a pet summoned: B follows within the check; (4b, the review) a pet with nothing
to stop (following or Stay-parked: no live pet target): no Pet Follow, Back as before; (5) a room / a guest holding, a game
window open: B is theirs; (6) a fight: the first B is the fight's give-back + stop attack; m171 (the user, 2026-11-17: our "Out of
combat" rule, not the game's): with the bars given back (our out-of-combat layout) B runs Pet Follow even though the game is still in
combat (an attacking pet keeps its owner in combat), then stop attack + clear target; nothing protected from insecure code, Pet Follow
after the fight; (6b) m173 (the user, 2026-11-17: "One B does both"): Smart pet on, the first B in a fight (the give-back) runs Pet
Follow too, then stops the attack; Smart pet off (or no live pet target, a dead pet): the give-back alone, unchanged; (7) the fight layout out of combat (an attack running): the first B is the give-
back, the next one (the rest layout) adds Pet Follow; (8) a trigger held: the layer's button, no Pet Follow; (9) Party mode: its own B;
(10) a non-pet class: never (a Mage's / Priest's pet bar included, the review); (11) the word in a fight: kept, the binding at the fight's end; (12) no trace (no spied call, no snippet error).
Needs lupa. Exit code 1 on any failure."""
import json
import os
import sys

from wowstub import ROOT, SRC, boot_world, docs, nsent

fails = passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' and not ok else ''))
    if ok:
        passes += 1
    else:
        fails += 1


# Forever's top bar button 7 (B's NonBindable Back) as a real global button that logs its press, so a router press can be followed to it
BACK = r'''
CLASS = "%s"
function UnitIsDead(u) local x = UNITS[u]; return x ~= nil and not x.alive end
local top = GamepadMainActionBarFrame.PageUnit.TopCenteredAnchor.Bar
local get = top.GetActionButtonByIndex
top.GetActionButtonByIndex = function(self, pos)
    local name = "GPTop" .. pos
    local b = rawget(_G, name)
    if b then return b end
    b = get(self, pos)
    b._tpl = ""
    b.clicks = { "AnyDown", "AnyUp" }
    b.scripts.OnClick = function(_, _, down) ACTIONS[#ACTIONS + 1] = { kind = "bar", btn = name, down = down and true or false } end
    rawset(_G, name, b)
    return b
end
'''


def world(cls="HUNTER", pet=True, pre='', at=True):
    """`at`: the pet is at something (attacking, or on its way: a live pet target), the case B's Pet Follow is for"""
    p = (BACK % cls + ('UNITS.pet = { name = "Wolf", alive = true }\n' if pet else '')
         + ('UNITS.pettarget = { name = "Boar", alive = true, foe = true }\n' if pet and at else '') + pre)
    L, errs = boot_world(pre=p)
    L.execute('LOADERRS = {}')
    for e in errs:
        L.eval('function(s) LOADERRS[#LOADERRS + 1] = s end')(e)
    L.execute('__IN.ACT.trust(); advance(1); __IN.N.setMode(true); advance(1)')
    settle(L)
    return L


def lst(L, expr):
    t = L.eval(expr)
    return [] if t is None else list(t.values())


def clean(L):
    return {k: v for k, v in (("VIOL", lst(L, 'VIOL')), ("SPY", lst(L, 'SPY')), ("HANDLEERR", lst(L, 'HANDLEERR')),
                              ("SNIPERR", lst(L, 'SNIPERR')), ("ERRS", lst(L, 'ERRS')), ("LOADERRS", lst(L, 'LOADERRS'))) if v}


def settle(L):
    L.execute('NOW = NOW + 2; tick(0.1); advance(0.6); NOW = NOW + 2; tick(0.1); advance(0.6)')


def press(L, key, lt=False, rt=False):
    a0, g0 = int(L.eval('#ACTIONS')), int(L.eval('#GAMEKEYS'))
    how, log = L.eval('hwPress')(key, lt, rt)
    L.execute('advance(0.2)')
    acts = [dict(L.eval('ACTIONS[%d]' % i)) for i in range(a0 + 1, int(L.eval('#ACTIONS')) + 1)]
    return {"how": how, "clicks": list(log.values()) if log else [], "acts": acts,
            "game": [L.eval('GAMEKEYS[%d]' % i) for i in range(g0 + 1, int(L.eval('#GAMEKEYS')) + 1)]}


def lines(r):
    """what one press did, in order: macro lines and Forever's bar button edges"""
    out = []
    for a in r['acts']:
        if a.get('kind') == 'macroline':
            out.append(a.get('line'))
        elif a.get('kind') == 'bar':
            out.append(a.get('btn') + ('+' if a.get('down') else '-'))
    return out


def padtab(L, *cols):
    for c in cols:
        L.eval('hwTap')("padtab", c)
        L.execute('advance(0.1)')


def tell(L, on):
    padtab(L, 39, 37 if on else 36)
    settle(L)


def qtap(L, col):
    L.eval('hwTap')("qact", col)
    L.execute('advance(0.3)')


def pf(L, n0=0):
    pm = docs(L, n0, 'padmap')
    return pm[-1]['m'].get('pf') if pm else None


def bkey(L):
    return L.eval('BIND["PAD2"]')


OURS_B = "CLICK WowCompanionFaceB:LeftButton"
BACK_PRESS = ['GPTop7+', 'GPTop7-']            # Forever's Back: its down and up edges, as the router's native press makes them

# ---------------------------------------------------------------- (0) the versions
check(int(SRC["padmap"].split('local PV = "m', 1)[1].split('"', 1)[0]) >= 174 and 'local BV = "b76"' in SRC["book"],
      "PadMap m174 or later, Book b68 (B: pet recall; m173 one B does both)")
check("One B does both" in SRC["padmap"] and "Off as picked" in SRC["padmap"], "m173: PadMap's header note quotes the user's picks (2026-11-17)")
check("I want a pet class specific option where pressing B in the out of combat home menu basically stops the pet attacking" in SRC["padmap"]
      and "basically stops the pet attacking" in SRC["book"], "both header notes quote the user")
check("the pet follow on b does not work out of combat, I wonder if we made this as an actual out of combat function" in SRC["padmap"],
      "m171: PadMap's header note quotes the user (2026-11-17)")
check('SecureCmdOptionParse("[nocombat,@pet' not in SRC["padmap"]
      and SRC["padmap"].count('SecureCmdOptionParse("[@pet,exists,nodead] 1") == "1"') == 2
      and SRC["padmap"].count('petf = "/petfollow\\n"') == 2,
      "m171: the snippet's check is OUR layout rule (rest) and a live pet, never the game's [nocombat]; the game's own /petfollow")
check(SRC["padmap"].count('SecureCmdOptionParse("[@pettarget,exists,nodead] 1") == "1" then petf') == 2,
      "m165 review: and only while the pet is at something (a live pet target; m173: the give-back's check too)")
check('(self:GetAttribute("wc-petf") or self:GetAttribute("wc-prc")) and SecureCmdOptionParse' in SRC["padmap"]
      and SRC["padmap"].count('self:SetAttribute("macrotext", (petf or "") .. bmacro)') == 1,
      "m173 / m174: the give-back adds Pet Follow only with pet recall's word, inside the snippet, petf .. bmacro")

# ---------------------------------------------------------------- (1) before the app's word: B exactly as before
W = world()
check(clean(W) == {}, "the world loads (a Hunter, a pet out), strict, clean", clean(W))
d = pf(W)
check(d is not None and d.get('cls') is True and d.get('pet') is True and d.get('on') is None and d.get('b') is None,
      "the padmap's pf: a pet class, a live pet, no word yet, B not carried", d)
check(bkey(W) is None, "no word: B is not ours (the game's own binding)", bkey(W))
r = press(W, "PAD2")
check(r['how'] == 'game' and r['game'] == ['PAD2+', 'PAD2-'] and '/petfollow' not in lines(r), "no word: B is the game's (Forever's Back), no Pet Follow", r)

# ---------------------------------------------------------------- (2) the word on: Pet Follow, then Forever's Back
n0 = nsent(W)
tell(W, True)
d = pf(W, n0)
check(d is not None and d.get('on') is True and d.get('b') is True, "padtab 39 then 37: the word on, B carried (pf.on, pf.b)", d)
check(bkey(W) == OURS_B, "B is the router's own button (an override of ours, never saved)", bkey(W))
n0 = nsent(W)
r = press(W, "PAD2")
check(lines(r) == ['/petfollow'] + BACK_PRESS, "B on Home, out of combat, a pet out: /petfollow first, then Forever's Back (down, up) as before",
      lines(r))
fc = [x for x in docs(W, n0, 'facecast') if x.get('pos') == 7 and x.get('down')]
check(fc and fc[-1].get('pf') is True and fc[-1].get('kind') == 'native', "the facecast says the press ran Pet Follow (pf), kind native", fc)
r = press(W, "PAD2")
check(lines(r) == ['/petfollow'] + BACK_PRESS, "every press: the same (a pet attacking with the player out of combat is called back)", lines(r))
check(W.eval('#SPY') == 0 and clean(W) == {}, "no spied call (no binding saved, no macro, no CVar), no error", clean(W))

# ---------------------------------------------------------------- (3) the word off: the game's again
n0 = nsent(W)
tell(W, False)
d = pf(W, n0)
check(d is not None and d.get('on') is None and d.get('b') is None and bkey(W) is None, "padtab 39 then 36: the word off, B the game's again", (d, bkey(W)))
r = press(W, "PAD2")
check(r['how'] == 'game' and '/petfollow' not in lines(r), "off: B is the game's own Back, no Pet Follow", r)
padtab(W, 37)
settle(W)
check(bkey(W) is None, "37 without the pick is not the word", bkey(W))

# ---------------------------------------------------------------- (3b) A / X / Y routed (a fight spell on X): B was already the router's (m21)
FACE = 'WowCompanionSpellsDB.faceBy = { [WHO .. "-" .. REALM] = { [5] = { id = 403, n = "Lightning Bolt", i = 1 } } }\n'
V = world(pre=FACE)
check(bkey(V) == OURS_B, "a fight spell on X: B is the router's before any word (m21, as before)", bkey(V))
r = press(V, "PAD2")
check(lines(r) == BACK_PRESS, "no word: the router's B is Forever's Back alone (as before)", lines(r))
tell(V, True)
r = press(V, "PAD2")
check(bkey(V) == OURS_B and lines(r) == ['/petfollow'] + BACK_PRESS, "the word on: the same button, Pet Follow first", lines(r))
tell(V, False)
r = press(V, "PAD2")
check(bkey(V) == OURS_B and lines(r) == BACK_PRESS, "the word off: still the router's (X's fight spell), Back alone again", (bkey(V), lines(r)))
check(clean(V) == {}, "faces routed: clean", clean(V))

# ---------------------------------------------------------------- (4) no pet, a dead pet, a pet summoned
N = world(pet=False)
tell(N, True)
d = pf(N)
check(d.get('on') is True and d.get('pet') is None and d.get('b') is None and bkey(N) is None, "no pet out: B not carried (the game's)", (d, bkey(N)))
r = press(N, "PAD2")
check(r['how'] == 'game' and '/petfollow' not in lines(r), "no pet: B is the game's Back", r)
N.execute('UNITS.pet = { name = "Imp", alive = true }; UNITS.pettarget = { name = "Boar", alive = true, foe = true }; fire("UNIT_PET", "player")')
settle(N)
check(bkey(N) == OURS_B, "a pet summoned: B carried within the check", bkey(N))
r = press(N, "PAD2")
check(lines(r) == ['/petfollow'] + BACK_PRESS, "then B calls it back", lines(r))
N.execute('UNITS.pet.alive = false')
r = press(N, "PAD2")
check(lines(r) == BACK_PRESS, "the pet dead before the check runs: the snippet's own [nodead] says no Pet Follow (Back only)", lines(r))
settle(N)
check(bkey(N) is None, "the pet dead: B not carried any more (the game's own)", bkey(N))
N.execute('UNITS.pet = nil; fire("UNIT_PET", "player")')
settle(N)
check(bkey(N) is None and pf(N).get('pet') is None, "the pet gone: the game's B", (bkey(N), pf(N)))
check(clean(N) == {}, "no pet / dead pet: clean", clean(N))

# ---------------------------------------------------------------- (4b) m165 review: a pet with nothing to stop is left alone
S = world(at=False)
tell(S, True)
check(bkey(S) == OURS_B, "a pet out, following (no pet target): B carried (the snippet decides each press)", bkey(S))
r = press(S, "PAD2")
check(lines(r) == BACK_PRESS, "a following / Stay-parked pet: no Pet Follow (never pulled off its Stay), Forever's Back as before", lines(r))
S.execute('UNITS.pettarget = { name = "Boar", alive = false, foe = true }')
r = press(S, "PAD2")
check(lines(r) == BACK_PRESS, "the pet's target dead: nothing to stop, Back only", lines(r))
S.execute('UNITS.pettarget = { name = "Boar", alive = true, foe = true }')
r = press(S, "PAD2")
check(lines(r) == ['/petfollow'] + BACK_PRESS, "the pet sent at a live target: B calls it back", lines(r))
check(clean(S) == {}, "a pet with nothing to stop: clean", clean(S))

# ---------------------------------------------------------------- (5) a room / a guest holding, a game window open: theirs
QACT = {"quests": 8, "map": 10}
R = world()
tell(R, True)
qtap(R, QACT["quests"])
settle(R)
check(R.eval('WowCompanion.capture.state().holder') == 'pad:quests' and bkey(R) != OURS_B,
      "a room holds (the Quests room's guest): the router steps aside, B is the room's", (R.eval('WowCompanion.capture.state().holder'), bkey(R)))
r = press(R, "PAD2")
check('/petfollow' not in lines(r), "B in a room: its own B chain, no Pet Follow", r)
qtap(R, QACT["quests"] + 1)
settle(R)
check(R.eval('WowCompanion.capture.state().holder') is None and bkey(R) == OURS_B, "the room lets go: B is Home's Pet Follow again",
      (R.eval('WowCompanion.capture.state().holder'), bkey(R)))
R.execute('mkwin("MerchantFrame", true)')
settle(R)
r = press(R, "PAD2")
check(bkey(R) != OURS_B and '/petfollow' not in lines(r), "a game window open (a vendor): the router aside, B the window's", (bkey(R), r))
R.execute('MerchantFrame:Hide()')
settle(R)
check(bkey(R) == OURS_B, "the window closed: ours again", bkey(R))
check(clean(R) == {}, "rooms and windows: clean", clean(R))

# ---------------------------------------------------------------- (6) a fight: B's fight rules, no Pet Follow
F = world(pre='UNITS.target = { name = "Kobold", alive = true, foe = true }')
tell(F, True)
F.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(0.5)')
check(bkey(F) == OURS_B, "the pull: B carried for the fight (as before)", bkey(F))
check(F.eval('WowCompanionFaceB.a["wc-state"]') == "fight", "the game's combat: the fight layout (the [combat] driver)",
      F.eval('WowCompanionFaceB.a["wc-state"]'))
n0 = nsent(F)
r = press(F, "PAD2")
# m173 (the user, 2026-11-17: "One B does both"): Smart pet on, the first B in a fight (the give-back) runs Pet Follow too, then stops the attack
check(lines(r) == ['/petfollow', '/stopattack'],
      "m173: Smart pet on, in a fight the first B gives back, runs Pet Follow AND stops the attack (one B does both)", lines(r))
check(F.eval('WowCompanionCombatFace5.a["wc-given"]') is True, "m173: that first B still gives the bars back", F.eval('WowCompanionCombatFace5.a["wc-given"]'))
r = press(F, "PAD2")
# m171 (the user, 2026-11-17: "the pet follow on b does not work out of combat ... our 'Out of combat' rule that basically changes the
# action bar size and combat mode"): the bars given back = OUR out-of-combat layout, whatever the game's combat says (an attacking pet
# keeps its owner in combat) -> Pet Follow, then B's own stop attack + clear target
check(lines(r) == ['/petfollow', '/stopattack', '/cleartarget'],
      "m171: the next B (given back = our out-of-combat layout, the game still in combat): Pet Follow, then stop attack + clear target",
      lines(r))
fc = [x for x in docs(F, n0, 'facecast') if x.get('pos') == 7 and x.get('down')]
check(fc and fc[-1].get('pf') is True and fc[-1].get('fight') is True, "m171: the facecast says the press ran Pet Follow, in the game's fight", fc)
r = press(F, "PAD2")
check(lines(r)[:1] == ['/petfollow'], "m171: every B on the given-back layout in the fight: Pet Follow first", lines(r))
F.execute('UNITS.pettarget = nil')
r = press(F, "PAD2")
check('/petfollow' not in lines(r), "m171: a pet with nothing to stop (no pet target) in the fight: left alone", lines(r))
F.execute('UNITS.pettarget = { name = "Kobold", alive = true, foe = true }')
check(lst(F, 'VIOL') == [] and lst(F, 'SPY') == [], "m171: the fight's presses: no protected call, no binding change in the lockdown",
      (lst(F, 'VIOL'), lst(F, 'SPY')))
n0 = nsent(F)
padtab(F, 39, 36)
padtab(F, 39, 37)
check(lst(F, 'VIOL') == [] and pf(F, n0).get('on') is True, "the word in a fight: kept, nothing protected from insecure code", (lst(F, 'VIOL'), pf(F, n0)))
F.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
settle(F)
check(bkey(F) == OURS_B, "the fight's end: B is Home's Pet Follow again", bkey(F))
r = press(F, "PAD2")
check(lines(r)[:1] == ['/petfollow'] and lines(r)[-2:] == BACK_PRESS, "after the fight: Pet Follow, then Forever's Back", lines(r))
check(clean(F) == {}, "the fight: no lockdown breach, no spied call, no snippet error", clean(F))

# ---------------------------------------------------------------- (6b) m173: the give-back B with Smart pet off / a pet with nothing to stop
G = world(pre='UNITS.target = { name = "Kobold", alive = true, foe = true }')
G.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(0.5)')
r = press(G, "PAD2")
check(lines(r) == ['/stopattack'], "m173: Smart pet off (no word), in a fight the first B: give-back + stop attack only (unchanged)", lines(r))
r = press(G, "PAD2")
check('/petfollow' not in lines(r), "m173: Smart pet off: no Pet Follow on the next B either (unchanged)", lines(r))
G.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
settle(G)
tell(G, True)
tell(G, False)
G.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(0.5)')
r = press(G, "PAD2")
check(lines(r) == ['/stopattack'], "m173: Smart pet told on then off: the first B in a fight is the give-back + stop attack only", lines(r))
H = world(pre='UNITS.target = { name = "Kobold", alive = true, foe = true }', at=False)
tell(H, True)
H.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(0.5)')
r = press(H, "PAD2")
check(lines(r) == ['/stopattack'], "m173: Smart pet on, the pet at nothing (no pet target): the first B in a fight leaves it alone", lines(r))
H.execute('UNITS.pettarget = { name = "Kobold", alive = true, foe = true }; UNITS.pet.alive = false')
H.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
settle(H)
H.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(0.5)')
r = press(H, "PAD2")
check('/petfollow' not in lines(r), "m173: Smart pet on, a dead pet: no Pet Follow on the give-back", lines(r))
check(clean(G) == {} and clean(H) == {}, "m173: the give-back cases: clean", (clean(G), clean(H)))

# ---------------------------------------------------------------- (7) the fight layout out of combat (an attack started, no fight yet)
A = world(pre='UNITS.target = { name = "Kobold", alive = true, foe = true }')
tell(A, True)
A.execute('fire("PLAYER_ENTER_COMBAT"); advance(0.3)')
check(A.eval('WowCompanionFaceB.a["wc-pre"]') is True, "an attack running out of combat: the controller grown (wc-pre)", A.eval('WowCompanionFaceB.a["wc-pre"]'))
r = press(A, "PAD2")
check(lines(r) == ['/petfollow', '/stopattack'], "m173: the fight layout's first B (Smart pet on): the give-back + Pet Follow + stop attack", lines(r))
r = press(A, "PAD2")
check(lines(r) == ['/petfollow', '/stopattack', '/cleartarget'],
      "the next B (the rest layout, out of combat): Pet Follow, then B's own clear target (as before)", lines(r))
check(clean(A) == {}, "the attack out of combat: clean", clean(A))

# ---------------------------------------------------------------- (8) a trigger held: the layer's button
r = press(R, "PAD2", lt=True)
check('/petfollow' not in lines(r), "LT + B: the layer's own button 7, no Pet Follow", lines(r))

# ---------------------------------------------------------------- (9) Party mode: its own B
P = world()
tell(P, True)
P.execute('WowCompanion.partyOk = true; if WowCompanion.partyAllow then WowCompanion.partyAllow(true) end; advance(0.5)')
settle(P)
press(P, "PADBACK")
pad = 'rawget(_G, "WowCompanionPartyPad")'
on = P.eval('%s ~= nil and %s.a["wc-party"] == true' % (pad, pad))
r = press(P, "PAD2")
check(on and '/petfollow' not in lines(r), "Party mode on (Select, a pet out): B is Party mode's (it closes it), no Pet Follow", (on, lines(r)))
check(clean(P) == {}, "Party mode: clean", clean(P))

# ---------------------------------------------------------------- (10) a class with no pet
X = world(cls="WARRIOR")
d = pf(X)
check(d is not None and d.get('cls') is None, "a Warrior: no pet class (the app hides the row)", d)
tell(X, True)
check(bkey(X) is None, "even told on: B is never carried for it", bkey(X))
r = press(X, "PAD2")
check(r['how'] == 'game' and '/petfollow' not in lines(r), "a Warrior's B: the game's own, as before", r)
for cls in ("WARLOCK", "DEATHKNIGHT"):
    C = world(cls=cls)
    check(pf(C).get('cls') is True, "%s: a pet class" % cls, pf(C))
for cls in ("MAGE", "PRIEST"):
    M = world(cls=cls, pre='function HasPetUI() return true, false end')
    check(pf(M).get('cls') is None, "m165 review: a %s with a pet bar (a Water Elemental, a Mind Control): not a pet class, no row" % cls,
          pf(M))
    tell(M, True)
    r = press(M, "PAD2")
    check(bkey(M) is None and '/petfollow' not in lines(r), "  and told on anyway: B never carried for it, the game's own", (bkey(M), lines(r)))

# ---------------------------------------------------------------- (11) a /reload forgets the word (in memory only)
W2 = world()
check(bkey(W2) is None and pf(W2).get('on') is None, "a new game session: off until the app says it again", (bkey(W2), pf(W2)))

for L in (W, V, N, S, R, F, G, H, A, P, X, M, W2):
    check(L.eval('#SPY') == 0 and L.eval('#SNIPERR') == 0, "no spied call, no snippet error", (L.eval('SPY[1]'), L.eval('SNIPERR[1]')))

# ---------------------------------------------------------------- the fixtures the app's PetFollowBTest reads (dsforever/src/test/resources/
# bpet-fixtures): the padmap's `pf` exactly as the add-on encodes it, in a padmap of its own (PARTY_FIXTURES_WRITE=1 rewrites them)
FIXDIR = os.path.join(ROOT, "dsforever", "src", "test", "resources", "bpet-fixtures")


def pfdoc(L):
    return L.eval('WowCompanion.encode({ type = "padmap", v = "m165", m = { pf = WowCompanion.collectPadMap().pf } })')


FIX = {"pf_hunter_no_word": pfdoc(W2)}
tell(W2, True)
FIX["pf_hunter_on"] = pfdoc(W2)
FIX["pf_warrior"] = pfdoc(world(cls="WARRIOR", pet=False))
NP = world(pet=False)
tell(NP, True)
FIX["pf_hunter_on_no_pet"] = pfdoc(NP)
# m174 B: PET RECALL: 0 = on with Home busy (only the fight's give-back B recalls: B not carried, no bf)
BZ = world()
tell(BZ, True)
padtab(BZ, 39, 0)
settle(BZ)
FIX["pf_hunter_on_busy"] = pfdoc(BZ)
check(bkey(BZ) is None, "m174: 0 (pet recall on, Home busy): B not carried for Pet Follow (the game's own B)", bkey(BZ))
BZ.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(0.5)')
r = press(BZ, "PAD2")
check(lines(r) == ['/petfollow', '/stopattack'], "m174: 0 (Home busy): the fight's first B still recalls (m173 as before, was wc-spet)", lines(r))
BZ.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
settle(BZ)
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
check(json.loads(FIX["pf_hunter_on"])["m"]["pf"] == {"on": True, "bf": True, "cls": True, "pet": True, "b": True}
      and json.loads(FIX["pf_hunter_no_word"])["m"]["pf"] == {"cls": True, "pet": True}
      and json.loads(FIX["pf_hunter_on_no_pet"])["m"]["pf"] == {"on": True, "bf": True, "cls": True}
      and json.loads(FIX["pf_hunter_on_busy"])["m"]["pf"] == {"on": True, "cls": True, "pet": True}
      and json.loads(FIX["pf_warrior"])["m"]["pf"] in ([], {}),
      "the fixtures' pf (m174: bf, no sp): told on / no word / no pet / Home busy / a Warrior", FIX)
check(not stale, "the committed fixtures equal what the add-on sends now (%d files; PARTY_FIXTURES_WRITE=1 rewrites)" % len(FIX), stale)

print("%d passed, %d failed" % (passes, fails))
print("ALL PASS" if fails == 0 else "FAILURES")
sys.exit(1 if fails else 0)
