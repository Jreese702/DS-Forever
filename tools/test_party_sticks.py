"""THE PARTY WHEELS' STICKS and THE PET'S Y (Radial r22, PadMap m168; the user, 2026-11-16: "the control sticks arent working on the
party inspect radials, they are still walking and looking around"; "pressing y on a pet in party mode does not open its radial"),
off-device: the REAL Safety / TapPad / Book / PadMap / PadTabCtl / Radial files under tools/wowstub.py's stubbed game, Lua 5.1 as the game's.

    python tools/test_party_sticks.py

Checks: a Party mode wheel (Y on a member, Y on the pet, a hold's MENU HOLD with Party mode on or off) shows Radial's stick catch with
BOTH sticks kept (mode nil) while no guest holds; MENU DONE (the wheel's close) hides it at once; a lost MENU DONE lets the sticks go
after 60 s; the catch shows and hides in a fight with nothing blocked; no list document goes out for a party wheel; the item wheel's
guest still shows it as before; a dead pet's Y opens nothing. Exit code 1 on a failure."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lupa import lua51 as lupa                    # noqa: E402,F401
from wowstub import ADDONS, boot, docs, nsent     # noqa: E402

RADIAL = os.path.join(ADDONS, "WowCompanionSpells", "Radial.lua")
RSRC = open(RADIAL, encoding="utf-8").read()
fails = passes = 0


def check(ok, name, got=''):
    global fails, passes
    if ok:
        passes += 1; print("PASS", name)
    else:
        fails += 1; print("FAIL", name, got)


HELPERS = r'''
function Frame:EnableGamePadStick(v) self._stick = v and true or false end
function Frame:IsGamePadStickEnabled() return self._stick == true end
function party(names)
    for k in pairs(UNITS) do if k:match("^party%d$") then UNITS[k] = nil end end
    for i, n in ipairs(names or {}) do
        UNITS["party" .. i] = { name = n, cls = "WARRIOR", alive = true, role = "NONE", pt = "RAGE", guid = "Player-1-" .. n }
    end
end
function router(pos) return rawget(_G, ({ [8] = "WowCompanionCombatFace8", [7] = "WowCompanionFaceB", [5] = "WowCompanionCombatFace5",
    [6] = "WowCompanionCombatFace6", [1] = "WowCompanionFaceD1", [2] = "WowCompanionFaceD2", [3] = "WowCompanionFaceD3",
    [4] = "WowCompanionFaceD4" })[pos]) end
function hit(pos) local b = router(pos); doClick(b, "LeftButton", true); doClick(b, "LeftButton", false) end
function PADA(k) return rawget(_G, "WowCompanionPartyPad").a[k] end
function clickName(n, down) doClick(rawget(_G, n), "LeftButton", down) end
function both(n) clickName(n, true); clickName(n, false) end
function shown() local c = rawget(_G, "WowCompanionRadialStickCatch"); return c ~= nil and c._shown == true end
function stick(s) local c = rawget(_G, "WowCompanionRadialStickCatch"); return c.scripts.OnGamePadStick(c, s, 0.9, 0, 0.9) end
function sync() tick(0.1) end
'''


def world():
    L = boot(["safety", "tappad", "book", "padmap", "padtabctl"], pre='WowCompanion.partyOk = true')
    L.execute(HELPERS)
    load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    load(RSRC, '@Radial.lua')("WowCompanionSpells")
    L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10); sync()')
    return L


def last_stick(L, start):
    d = docs(L, start, "radialstick")
    return d[-1] if d else None


check('local RV = "r27"' in RSRC and "the control sticks arent working on the party inspect radials" in RSRC,
      "Radial says r23 (r22p merged), its header note quotes the user")

W = world()
check(list(W.eval('ERRS').values()) == [] and W.eval('#SNIPERR') == 0, "the world loads (Safety, TapPad, Book, PadMap, PadTabCtl, Radial)",
      (list(W.eval('ERRS').values())[:2], W.eval('SNIPERR[1]')))
check(W.eval('shown()') is False, "nothing open: the catch is hidden (the game has the sticks)")

# ---------------------------------------------------------------- 1. Y on a member (Party mode on)
W.execute('party({ "Brannoc" }); both("WowCompanionPartyOn"); advance(0.3); sync()')
check(W.eval('PADA("wc-party")') is True and W.eval('shown()') is False, "Party mode on, no wheel: the catch stays hidden (walking works)")
W.execute('rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 2')           # you, party1 (no pet)
n0 = nsent(W)
W.execute('hit(6); sync()')
d = last_stick(W, n0)
check(W.eval('PADA("wc-k")') == "menu" and W.eval('PADA("wc-u")') == "party1" and W.eval('shown()') is True,
      "Y on party1: its wheel (k menu) and the catch SHOWN: the character does not walk, the camera does not turn",
      (W.eval('PADA("wc-k")'), W.eval('shown()')))
check(d is not None and d.get('on') is True and d.get('party') is True and d.get('mode') is None,
      "the radialstick doc: on, party, mode nil (both sticks kept)", d)
check(W.eval('stick("Left")') is False and W.eval('stick("Right")') is False and W.eval('stick("Movement")') is False,
      "both sticks kept from the game (the handler answers false)")
check(not docs(W, n0, "radial") and not docs(W, n0, "sradial"), "a party wheel sends no item / spell radial list")
n0 = nsent(W)
W.execute('both("WowCompanionPartyMenuDone"); sync()')
d = last_stick(W, n0)
check(W.eval('PADA("wc-pmenu")') is None and W.eval('shown()') is False and d and d.get('on') is False,
      "MENU DONE (the wheel's close): hidden at once, the sticks back to the game", d)

# ---------------------------------------------------------------- 2. Y on the pet (PadMap m168)
W.execute('UNITS.pet = { name = "Wolf", alive = true }; party({ "Brannoc" }); fire("UNIT_PET", "player"); advance(0.3); sync()')
W.execute('rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 2; hit(6); sync()')
check(W.eval('PADA("wc-k")') == "menu" and W.eval('PADA("wc-u")') == "pet" and W.eval('shown()') is True,
      "m168: Y on the pet: its wheel (k menu, u pet) and the catch shown", (W.eval('PADA("wc-k")'), W.eval('PADA("wc-u")'), W.eval('shown()')))
pb = [x for x in docs(W, 0, "partybox") if x.get('b', {}).get('k') == "menu"]
check(pb and pb[-1]['b'].get('u') == "pet" and pb[-1]['b'].get('pm') is True,
      "m168: the partybox the app opens the pet's wheel by: k menu, u pet, pm", pb[-1] if pb else None)
W.execute('both("WowCompanionPartyMenuDone"); sync()')
check(W.eval('shown()') is False, "the pet's wheel closed: hidden")
W.execute('UNITS.pet.alive = false; rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 2; hit(6); sync()')
check(W.eval('PADA("wc-pmenu")') is None and W.eval('PADA("wc-k")') != "menu" and W.eval('shown()') is False,
      "m168: Y on a dead pet: no wheel, the catch hidden", (W.eval('PADA("wc-k")'), W.eval('shown()')))
W.execute('UNITS.pet = nil; fire("UNIT_PET", "player"); advance(0.3); sync()')

# ---------------------------------------------------------------- 3. a hold's MENU HOLD (Party mode on, then off)
W.execute('both("WowCompanionPartyMenuHold"); sync()')
check(W.eval('PADA("wc-pmenu")') is True and W.eval('shown()') is True, "a hold with Party mode on (MENU HOLD): the catch shown")
W.execute('both("WowCompanionPartyMenuDone"); sync()')
check(W.eval('shown()') is False, "its MENU DONE: hidden")
W.execute('both("WowCompanionPartyOff"); advance(0.3); sync()')
check(W.eval('PADA("wc-party")') is None and W.eval('shown()') is False, "Party mode off: hidden")
W.execute('both("WowCompanionPartyMenuHold"); sync()')
check(W.eval('PADA("wc-pmenu")') is None and W.eval('shown()') is True,
      "a hold with Party mode off (MENU HOLD: no sub-state): the catch shown all the same")
W.execute('both("WowCompanionPartyMenuDone"); sync()')
check(W.eval('shown()') is False, "its MENU DONE: hidden")

# ---------------------------------------------------------------- 4. a lost MENU DONE never keeps the sticks
W.execute('both("WowCompanionPartyMenuHold"); sync(); advance(59); sync()')
check(W.eval('shown()') is True, "MENU HOLD, 59 s on: still shown (a wheel being read)")
W.execute('advance(2); sync()')
check(W.eval('shown()') is False, "MENU HOLD, 61 s on with no MENU DONE: hidden (the sticks back)")
W.execute('both("WowCompanionPartyMenuDone"); both("WowCompanionPartyOn"); advance(0.3); rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 2; '
          'hit(6); sync(); advance(61); sync()')
check(W.eval('PADA("wc-pmenu")') is True and W.eval('shown()') is False, "a stranded menu sub-state, 61 s on: hidden")
W.execute('both("WowCompanionPartyMenuDone"); sync()')

# ---------------------------------------------------------------- 5. in a fight
W.execute('COMBAT = true; both("WowCompanionPartyMenuHold"); sync()')
check(W.eval('shown()') is True and W.eval('#VIOL') == 0, "in a fight: a hold's wheel shows the catch, nothing blocked", W.eval('VIOL[1]'))
W.execute('both("WowCompanionPartyMenuDone"); sync()')
check(W.eval('shown()') is False and W.eval('#VIOL') == 0, "in a fight: its close hides it, nothing blocked", W.eval('VIOL[1]'))
W.execute('COMBAT = false')

# ---------------------------------------------------------------- 6. the other wheels as before
n0 = nsent(W)
W.execute('HOLDER = "pad:radial"; sync()')
check(W.eval('shown()') is True, "the item wheel's guest: shown, as before")
check(bool(docs(W, n0, "radial")), "its opening sends the item list, as before")
W.execute('HOLDER = nil; sync()')
check(W.eval('shown()') is False, "its free: hidden")
# the party catch already shown (a lost MENU DONE), then a real wheel opens: its lists still go out at that opening
W.execute('both("WowCompanionPartyMenuHold"); sync()')
check(W.eval('shown()') is True, "a stranded party hold: shown")
n0 = nsent(W)
W.execute('HOLDER = "pad:radial"; sync()')
check(W.eval('shown()') is True and bool(docs(W, n0, "radial")),
      "the item wheel opens over it: its item list is sent all the same (never a stale list)")
W.execute('HOLDER = nil; both("WowCompanionPartyMenuDone"); sync()')
check(W.eval('shown()') is False, "both closed: hidden")
check(W.eval('#SNIPERR') == 0 and list(W.eval('ERRS').values()) == [], "no snippet / script error", W.eval('SNIPERR[1]'))

print("%d passed, %d failed" % (passes, fails))
if fails:
    sys.exit(1)
print("ALL PASS")
