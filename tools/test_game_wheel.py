"""THE GAME'S OWN WHEEL SHUT ONCE MORE (issue #306; ActionBar gws2, b-game-wheel-shut2; the user's pick 2026-11-16: "if the game's own
pet/spell flyout ... is still shown ~0.5 s after our class wheel lets go and no room holds the pad, make it visible (alpha 1) and close it
once more the safe way"), off-device: the REAL block of ActionBar.lua (GAMEWHEEL-BEGIN .. GAMEWHEEL-END) under tools/wowstub.py's stubbed
game, Lua 5.1 as the game's.

    python tools/test_game_wheel.py

  a the set-up: our secure shut button, a frame ref and a Show post-hook on each of the game's three gamepad wheels;
  b a wheel shown while a class chord is held: alpha 0 (as before) and watched;
  c the chord let go while our class wheel's guest "pad:class" (or any room) holds: nothing, however long;
  d nobody holds: after 0.5 s (not before) the wheel is drawn again (alpha 1) and ONE "gamewheel" document asks for the shut tap
    (w, col 24 while the cell is built as the shut, v gws2); never a second for the same opening; the poll stops;
  e the app's tap (the secure snippet) hides it; a new opening whose shut lands in time sends nothing;
  f the chord pressed again before 0.5 s: the clock starts again;
  g a wheel the player opened without a chord (a bar button's flyout), or with our class wheel off: alpha 1, never watched;
  h no shut cell built (a fight's change waits): the document has no col;
  i in a fight: the same, insecure code calls nothing protected (no lockdown violation), no Blizzard Show / Hide / open / close call,
    no binding, macro or CVar.
Needs lupa. Exit code 1 on any failure."""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import ADDONS, boot, docs  # noqa: E402

AB = open(os.path.join(ADDONS, "WowCompanion", "ActionBar.lua"), encoding="utf-8").read()
BLOCK = re.search(r'-- GAMEWHEEL-BEGIN[^\n]*\n(.*?)-- GAMEWHEEL-END', AB, re.S).group(1)

fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


PRE = r'''
KEYS = {}
function IsKeyDown(k) return KEYS[k] == true end
function Frame:SetAlpha(a) self._alpha = a end
function Frame:GetAlpha() return self._alpha or 1 end
-- the game's three wheels: Blizzard frames; an insecure Show / Hide on them is recorded (it must never happen)
BADSH = {}
WHEELS = {}
for i, n in ipairs({ "GamepadPetActionFlyout", "GamepadPetSpellFlyout", "GamepadSpellFlyout" }) do
    local w = setmetatable({ a = {}, refs = {}, hooks = {}, scripts = {}, wraps = {}, clicks = {}, _name = n, _tpl = "", _shown = false }, Frame)
    rawset(_G, n, w)
    WHEELS[i] = w
end
-- the game opens / closes its wheel (its own secure code: never ours)
function gameShows(i) local was = IN_SECURE; IN_SECURE = true; WHEELS[i]:Show(); IN_SECURE = was end
function gameHides(i) local was = IN_SECURE; IN_SECURE = true; WHEELS[i]:Hide(); IN_SECURE = was end
for _, w in ipairs(WHEELS) do
    local show, hide = Frame.Show, Frame.Hide
    w.Hide = function(self) if not IN_SECURE then BADSH[#BADSH + 1] = "Hide " .. self._name end; hide(self) end
    w.Show = function(self) if not IN_SECURE then BADSH[#BADSH + 1] = "Show " .. self._name end; show(self) end
end
'''


def load(L):
    f = L.eval('function(src) local f, e = loadstring("local WC = _G.WowCompanion\\nlocal S, SN, CALL = WC.S, WC.SN, WC.CALL\\n'
               'local CR = { want = {}, frozen = {} }\\n_G.__CR = CR\\n" .. src .. "\\n_G.__GF = GF\\n", "@ActionBar.lua#gamewheel"); '
               'if not f then error(e) end; return f end')
    f(BLOCK)()


def gw(L, start=0):
    return docs(L, start, "gamewheel")


def alpha(L, i):
    return L.eval('WHEELS[%d]:GetAlpha()' % i)


def shown(L, i):
    return bool(L.eval('WHEELS[%d]:IsShown()' % i))


def shut(L):
    """the app's tap on the classr shut cell: our secure snippet runs (a hardware click)"""
    L.execute('runSecure(compileSnippet(__GF.btn.a._onclick, "self, button, down"), __GF.btn, "LeftButton", false)')


def chord(L, on, side="L"):
    a, b = ("PADLSHOULDER", "PADLTRIGGER") if side == "L" else ("PADRSHOULDER", "PADRTRIGGER")
    L.execute('KEYS["%s"] = %s; KEYS["%s"] = %s' % (a, 'true' if on else 'nil', b, 'true' if on else 'nil'))


L = boot(["safety"], pre=PRE)
load(L)

# ================================================================ a the set-up
L.execute('WowCompanion.classWheelOn = true; WowCompanion.gameWheelShut.setup()')
check(L.eval('__GF.btn') is not None and L.eval('__GF.btn._tpl') == "SecureHandlerClickTemplate", "a. our shut button (a secure handler)")
check(int(L.eval('__GF.refs')) == 3 and all(L.eval('__GF.btn.refs["wheel%d"]' % i) is not None for i in (1, 2, 3)),
      "a. a frame ref on each of the game's three wheels")
check(L.eval('WowCompanion.gameWheelShut.v') == "gws2" and int(L.eval('WowCompanion.gameWheelShut.col')) == 24, "a. tag gws2, the shut cell 24")
# the classr row's shut cell, built as the shut
L.execute('__CR.want[24] = { type = "click", sig = "gf3" }; __CR.frozen[24] = "gf3"')

# ================================================================ b-d the chord, the hold, the quiet
chord(L, True)
L.execute('gameShows(2)')
check(alpha(L, 2) == 0 and L.eval('__GF.watch[WHEELS[2]]') is not None, "b. shown under a held chord: alpha 0, watched")
chord(L, False)
L.execute('HOLDER = "pad:class"')
n0 = len(gw(L))
L.execute('advance(2.0)')
check(len(gw(L)) == n0 and alpha(L, 2) == 0, "c. let go while our class wheel's guest holds: nothing after 2 s", (len(gw(L)), alpha(L, 2)))
L.execute('HOLDER = "pad:quests"; advance(1.0)')
check(len(gw(L)) == n0 and alpha(L, 2) == 0, "c. a room holds: nothing")
L.execute('HOLDER = nil; advance(0.35)')
check(len(gw(L)) == n0 and alpha(L, 2) == 0, "d. nobody holds for 0.35 s: not yet")
L.execute('advance(0.3)')
d = gw(L)
check(len(d) == n0 + 1, "d. after 0.5 s: one gamewheel document", len(d) - n0)
doc = d[-1] if d else {}
check(doc.get("w") == 2 and doc.get("col") == 24 and doc.get("v") == "gws2" and doc.get("fight") is None,
      "d. it names the wheel (2), the shut cell 24, tag gws2, out of a fight", doc)
check(alpha(L, 2) == 1, "d. the wheel drawn again: alpha 1", alpha(L, 2))
L.execute('advance(5.0)')
check(len(gw(L)) == n0 + 1, "d. never a second document for the same opening", len(gw(L)) - n0)
check(not L.eval('__GF.polling'), "d. the poll has stopped (nothing left to watch)")
shut(L)
check(not shown(L, 2), "e. the app's tap: our secure snippet hides it")
L.execute('advance(1.0)')
check(L.eval('__GF.watch[WHEELS[2]]') is None, "e. a hidden wheel is no longer watched")

# ================================================================ e a shut that lands in time
n1 = len(gw(L))
chord(L, True, "R")
L.execute('gameShows(1)')
chord(L, False, "R")
L.execute('advance(0.2)')
shut(L)
L.execute('advance(3.0)')
check(len(gw(L)) == n1 and not shown(L, 1), "e. the close tap landed in time: no document", len(gw(L)) - n1)

# ================================================================ f the chord again before 0.5 s
chord(L, True)
L.execute('gameShows(3)')
chord(L, False)
L.execute('advance(0.4)')
chord(L, True)
L.execute('advance(0.4)')
chord(L, False)
L.execute('advance(0.35)')
check(len(gw(L)) == n1, "f. the chord pressed again at 0.4 s: the clock started again (nothing at 0.75 s from the first let-go)")
L.execute('advance(0.3)')
check(len(gw(L)) == n1 + 1 and gw(L)[-1].get("w") == 3, "f. 0.5 s after the last let-go: the document", gw(L)[-1] if gw(L) else None)
shut(L)

# ================================================================ g no chord / our class wheel off
n2 = len(gw(L))
L.execute('gameShows(3)')                                     # a bar button's flyout: the player's own
check(alpha(L, 3) == 1 and L.eval('__GF.watch[WHEELS[3]]') is None, "g. opened without a chord: alpha 1, not watched")
L.execute('advance(3.0)')
check(len(gw(L)) == n2 and shown(L, 3), "g. ... and left alone (no document, still open)")
L.execute('gameHides(3)')
L.execute('WowCompanion.classWheelOn = false')
chord(L, True)
L.execute('gameShows(1)')
chord(L, False)
L.execute('advance(3.0)')
check(alpha(L, 1) == 1 and len(gw(L)) == n2, "g. our class wheel off: the game's wheel is the player's (alpha 1, no document)")
L.execute('gameHides(1); WowCompanion.classWheelOn = true')

# ================================================================ h the shut cell not built
L.execute('__CR.frozen[24] = nil')
chord(L, True)
L.execute('gameShows(1)')
chord(L, False)
L.execute('advance(0.7)')
dh = gw(L)[-1] if len(gw(L)) > n2 else {}
check(dh.get("w") == 1 and "col" not in dh and alpha(L, 1) == 1, "h. no shut cell built: the document has no col, the wheel in sight", dh)
L.execute('gameHides(1); __CR.frozen[24] = "gf3"')

# ================================================================ i in a fight
n3 = len(gw(L))
L.execute('COMBAT = true')
chord(L, True, "R")
L.execute('gameShows(2)')
check(alpha(L, 2) == 0, "i. a fight: drawn unseen under the chord")
chord(L, False, "R")
L.execute('advance(0.7)')
di = gw(L)[-1] if len(gw(L)) > n3 else {}
check(di.get("w") == 2 and di.get("fight") is True and di.get("col") == 24 and alpha(L, 2) == 1,
      "i. a fight: drawn again, the document (fight) asks for the shut tap", di)
shut(L)
check(not shown(L, 2), "i. a fight: the secure snippet hides it (the tap is the ruled route)")
L.execute('COMBAT = false')
check(int(L.eval('#VIOL')) == 0, "i. no protected call from insecure code", [L.eval('VIOL[%d]' % (k + 1)) for k in range(int(L.eval('#VIOL')))])
check(int(L.eval('#BADSH')) == 0, "i. no Show / Hide of the game's wheels by insecure code",
      [L.eval('BADSH[%d]' % (k + 1)) for k in range(int(L.eval('#BADSH')))])
check(int(L.eval('#SPY')) == 0, "i. no binding, macro, CVar or Blizzard window call", [L.eval('SPY[%d]' % (k + 1)) for k in range(int(L.eval('#SPY')))])
check(int(L.eval('#SNIPERR')) == 0, "the secure snippet ran without an error", [L.eval('SNIPERR[%d]' % (k + 1)) for k in range(int(L.eval('#SNIPERR')))])
check(int(L.eval('#ERRS')) == 0, "no Lua error")

print("\n%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
