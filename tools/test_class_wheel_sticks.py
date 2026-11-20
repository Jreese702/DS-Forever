"""CLASS WHEELS AIM WITH BOTH STICKS (Radial.lua r22, feat/class-wheel-sticks; the user 2026-11-16: "one other thing to note on this
would be its awkward only working from the right stick. I think that either stick should work those wheels.", pick: "Both sticks aim,
no walking"), off-device: the REAL Safety / TapPad / Radial files under tools/wowstub.py's stubbed game, Lua 5.1 as the game's.

    python tools/test_class_wheel_sticks.py

Checks: while the class wheel's guest "pad:class" holds, the stick catch is shown and keeps EVERY stick (Left / Movement too: the
character does not walk; r15's pass is gone); the item / spell / pause wheels' guest "pad:radial" still keeps every stick; with nobody
holding the catch is hidden and has no mode; the header quotes the user. Exit code 1 on a failure."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lupa import lua51 as lupa                    # noqa: E402
from wowstub import ADDONS, GAME, docs            # noqa: E402

RADIAL = os.path.join(ADDONS, "WowCompanionSpells", "Radial.lua")
FILES = [os.path.join(ADDONS, "WowCompanion", "Safety.lua"), os.path.join(ADDONS, "WowCompanion", "TapPad.lua"), RADIAL]
SRC = open(RADIAL, encoding="utf-8").read()
fails = passes = 0
STICKS = ("Left", "Movement", "Right", "Camera")


def check(ok, name, got=''):
    global fails, passes
    if ok:
        passes += 1; print("PASS", name)
    else:
        fails += 1; print("FAIL", name, got)


def world():
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(GAME)
    # the game's stick API on our frame (EnableGamePadStick: the catch's one protected call, out of combat)
    L.execute('''
function Frame:EnableGamePadStick(on) self._sticks = on and true or false end
function Frame:IsGamePadStickEnabled() return self._sticks == true end
''')
    load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    for p in FILES:
        load(open(p, encoding="utf-8").read(), '@' + os.path.basename(p))(os.path.basename(p))
    L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
    return L


def kept(L, stick):
    # the catch's answer for one stick event: false = kept from the game, true = passed on
    return L.eval('WowCompanion.radial.catch:GetScript("OnGamePadStick")(WowCompanion.radial.catch, "%s", 0.9, 0, 0.9)' % stick) is False


def hold(L, holder):
    L.execute('HOLDER = %s; WowCompanion.radial.stickSync()' % ('nil' if holder is None else '"%s"' % holder))


# ---------------------------------------------------------------- 0. the header
check('local RV = "r27"' in SRC, "Radial.lua's tag is r23 (r22c merged with r22p)")
check("either stick should work those wheels" in SRC and "Both sticks aim, no walking" in SRC and "REPLACES r15" in SRC,
      "the header quotes the user (the request and the pick) and says it replaces r15")

L = world()
check(L.eval('ERRS[1]') is None, "the files load and log in with no error", L.eval('ERRS[1]'))
check(L.eval('WowCompanion.radial.catch:IsGamePadStickEnabled()') is True, "the catch's sticks were enabled once, out of combat")

# ---------------------------------------------------------------- 1. the class wheel: both sticks kept
n0 = len(docs(L, 0, 'radialstick'))
hold(L, "pad:class")
check(L.eval('WowCompanion.radial.catch:IsShown()') is True, "pad:class holds: the catch shows")
check(L.eval('WowCompanion.radial.catch.mode') == "class", "its mode is class", L.eval('WowCompanion.radial.catch.mode'))
for s in STICKS:
    check(kept(L, s), "the class wheel keeps the %s stick from the game" % s)
check(L.eval('WowCompanion.radial.catch.pass') == 0, "nothing passed on (pass 0)", L.eval('WowCompanion.radial.catch.pass'))
check(L.eval('WowCompanion.radial.catch.n') == len(STICKS), "every event counted as caught", L.eval('WowCompanion.radial.catch.n'))
on = docs(L, 0, 'radialstick')[n0:]
check(on and on[-1].get('on') is True and on[-1].get('mode') == "class", "the radialstick doc says on, mode class", on)

# ---------------------------------------------------------------- 2. let go: hidden, no mode, no pass reported
hold(L, None)
check(L.eval('WowCompanion.radial.catch:IsShown()') is False, "nobody holds: the catch hides")
check(L.eval('WowCompanion.radial.catch.mode') is None, "no mode")
off = docs(L, 0, 'radialstick')[-1]
check(off.get('on') is False and off.get('pass') is None and off.get('n') == len(STICKS), "the off doc counts n, no pass", off)

# ---------------------------------------------------------------- 3. the item / spell / pause wheels: unchanged (both kept)
hold(L, "pad:radial")
check(L.eval('WowCompanion.radial.catch:IsShown()') is True and L.eval('WowCompanion.radial.catch.mode') is None,
      "pad:radial holds: the catch shows, no class mode")
for s in STICKS:
    check(kept(L, s), "the item / spell / pause wheels keep the %s stick" % s)
hold(L, None)

# ---------------------------------------------------------------- 4. another holder (a room): no catch
hold(L, "pad:quests")
check(L.eval('WowCompanion.radial.catch:IsShown()') is False, "a room's guest: no catch (the game keeps its sticks)")
hold(L, None)
check(L.eval('ERRS[1]') is None, "no error through it all", L.eval('ERRS[1]'))

print("\n%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
