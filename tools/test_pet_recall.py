"""B: PET RECALL ONLY (PadMap m174, Book b68, ActionBar cb12, Radial r25), off-device: the REAL add-on files (Safety, TapPad, Map, Quests,
Hub, Interact + Dialogs, Book, PadMap, PadTabCtl) under tools/wowstub.py's game with STRICT laid over it (the lockdown, the press model, the
restricted snippets).

    python -B tools/test_pet_recall.py

The user, 2026-11-18: "I like the pet recall but I think we need to remove the pet smart attack, its obnoxious". 2026-11-17's Smart pet
(PadMap m172) had two halves; the attack half (the game's "/petattack [pet,harm,nodead]" put into the press's own text on every attack
route) is GONE. The word stays padtab 39 then 36 off / 37 on with Home free / 0 on with Home busy, now recall only.

Proves: (0) the versions and notes, no "/petattack" line and no Smart pet hook left in the add-on's code; (1) before any word: the texts
(the m171 texts); (2) 37 and 0: every route's text byte-identical to (1) - the start-attack slot lines, X's Interact, the player's own
macros, our Start Attack command, a face's fight spell, the fight - and B's /petfollow as before (37), the fight's give-back B too (0);
(3) an add-on state that held Smart pet's "both" / "attack" (37 / 0, an old app) is recall only; (4) a class with no pet: nothing; no
spied call (no macro, binding or CVar saved). Needs lupa. Exit code 1 on any failure."""
import re
import sys

from wowstub import SRC, boot_world, docs, nsent

fails = passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' and not ok else ''))
    if ok:
        passes += 1
    else:
        fails += 1


# the game's bar slots (storage: set 1 = 1-12, LT 13-24, RT 25-36), the spells on them, harmful spells, the player's macros; every
# button of Forever's top bar logs its press (as test_b_pet_follow's BACK)
WORLD = r'''
CLASS = "%s"
function UnitIsDead(u) local x = UNITS[u]; return x ~= nil and not x.alive end
SPELLNAME[75] = "Auto Shot"; SPELLNAME[17253] = "Bite"; SPELLNAME[3044] = "Arcane Shot"
HARM = { [75] = true, [403] = true, [17253] = true, [3044] = true, ["Auto Shot"] = true, ["Lightning Bolt"] = true, ["Bite"] = true,
         ["Arcane Shot"] = true }
C_Spell.IsSpellHarmful = function(s) return HARM[s] == true end
C_Spell.GetSpellInfo = function(s)
    for id, n in pairs(SPELLNAME) do if id == s or n == s then return { spellID = id, iconID = 130000 + id, name = n } end end
end
ACTIONINFO = {}
function GetActionInfo(s) local a = ACTIONINFO[s]; if a then return a[1], a[2], a[3] end end
function GetActionTexture(s) local a = ACTIONINFO[s]; return a and 130000 + (tonumber(a[2]) or 0) or nil end
MACROS = {}                                   -- index -> { name, body, spell }
function GetActionText(s) local a = ACTIONINFO[s]; if a and a[1] == "macro" then return MACROS[a[2]][1] end end
function GetMacroIndexByName(n) for i, m in pairs(MACROS) do if m[1] == n then return i end end return 0 end
function GetMacroInfo(i) local m = MACROS[i]; if m then return m[1], 134400, m[2] end end
function GetMacroSpell(i) local m = MACROS[i]; return m and m[3] or nil end
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
for _, a in ipairs({ { "LeftCenteredAnchor", "GPLeft" }, { "RightCenteredAnchor", "GPRight" } }) do
    local bar = GamepadMainActionBarFrame.PageUnit[a[1]].Bar
    local g = bar.GetActionButtonByIndex
    bar.GetActionButtonByIndex = function(self, pos)
        local name = a[2] .. pos
        local b = rawget(_G, name)
        if b then return b end
        b = g(self, pos)
        b._tpl = ""
        b.clicks = { "AnyDown", "AnyUp" }
        b.scripts.OnClick = function(_, _, down) ACTIONS[#ACTIONS + 1] = { kind = "bar", btn = name, down = down and true or false } end
        rawset(_G, name, b)
        return b
    end
end
'''
# LT + X = Auto Shot (17), LT + Y = Healing Wave (18, helpful), LT + A = the pet's Bite (20), LT + D-pad left = Arcane Shot (13);
# RT + X = the player's macro "MyShot" (29: #showtooltip + /cast Auto Shot), RT + Y = "MyHeal" (30: /cast Healing Wave),
# RT + A = our Start Attack command (32, the overlay)
SLOTS = r'''
C_GamepadUI = { GetFirstGamepadActionStorageSlotIndex = function() return 1 end }   -- Book's storageFor: slot = (set - 1) * 12 + pos
Constants = { GamepadActionBarConstants = { NUM_SLOTS_PER_GAMEPAD_ACTION_BAR = 12, NUM_PAGEABLE_SLOTS_PER_GAMEPAD_ACTION_BAR_PAGE_UNIT_STANDARD_PAGE = 48,
                                            NUM_RESERVED_SLOTS_PER_GAMEPAD_ACTION_BAR_PAGE_UNIT = 0 } }
ACTIONINFO[13] = { "spell", 3044 }
ACTIONINFO[17] = { "spell", 75 }
ACTIONINFO[18] = { "spell", 331 }
ACTIONINFO[20] = { "spell", 17253, "pet" }
MACROS[1] = { "MyShot", "#showtooltip\n/cast Auto Shot", 75 }
MACROS[2] = { "MyHeal", "/cast Healing Wave", 331 }
ACTIONINFO[29] = { "macro", 1 }
ACTIONINFO[30] = { "macro", 2 }
WowCompanionSpellsDB.cmdBy = { [WHO .. "-" .. REALM] = { [32] = { k = "STARTATTACK", page = 1, set = 3, pos = 8 } } }
'''
FACE = 'WowCompanionSpellsDB.faceBy = { [WHO .. "-" .. REALM] = { [5] = { id = 403, n = "Lightning Bolt", i = 1 } } }\n'


def world(cls="HUNTER", pet=True, at=True, pre='', slots=True):
    p = (WORLD % cls + (SLOTS if slots else '') + ('UNITS.pet = { name = "Wolf", alive = true }\n' if pet else '')
         + ('UNITS.pettarget = { name = "Boar", alive = true, foe = true }\n' if pet and at else '')
         + 'UNITS.target = { name = "Kobold", alive = true, foe = true }\n' + pre)
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
    a0 = int(L.eval('#ACTIONS'))
    how, log = L.eval('hwPress')(key, lt, rt)
    L.execute('advance(0.2)')
    acts = [dict(L.eval('ACTIONS[%d]' % i)) for i in range(a0 + 1, int(L.eval('#ACTIONS')) + 1)]
    return {"how": how, "clicks": list(log.values()) if log else [], "acts": acts}


def lines(r):
    out = []
    for a in r['acts']:
        if a.get('kind') == 'macroline':
            out.append(a.get('line'))
        elif a.get('kind') == 'bar':
            out.append(a.get('btn') + ('+' if a.get('down') else '-'))
        elif a.get('kind') not in (None, 'macro'):
            out.append(a.get('kind') + ':' + str(a.get('spell')))
    return out


def padtab(L, *cols):
    for c in cols:
        L.eval('hwTap')("padtab", c)
        L.execute('advance(0.1)')


def tell(L, code):
    """36 = off, 37 = on with Home free (B too), 0 = on with Home busy (the fight's give-back B only)"""
    padtab(L, 39, code)
    settle(L)


def pf(L, n0=0):
    pm = docs(L, n0, 'padmap')
    return pm[-1]['m'].get('pf') if pm else None


def hdr(L, k):
    return L.eval('rawget(_G, "WowCompanionFaceRouter").a["%s"]' % k)


def clicked(r, name):
    return any(c == name + '+' for c in r['clicks'])


SOFT = "/target [noexists][dead] softenemy"
SPET = "/petattack [pet,harm,nodead]"
SA = ["/startattack [harm,nodead]", "/click [harm,nodead] WowCompanionFaceRearm"]
ATTACK_LINES = "/target [noexists][dead] softenemy\n/startattack [harm,nodead]\n/click [harm,nodead] WowCompanionFaceRearm\n"
OURS_B = "CLICK WowCompanionFaceB:LeftButton"

# ---------------------------------------------------------------- (0) the versions, the notes, nothing of the attack half left
PM = SRC["padmap"]
CODE = re.sub(r'--[^\n]*', '', PM)          # the code without its comments (the notes name what is gone)
check(int(PM.split('local PV = "m', 1)[1].split('"', 1)[0]) >= 174 and 'local BV = "b76"' in SRC["book"], "PadMap m174, Book b68")
Q = "I like the pet recall but I think we need to remove the pet smart attack, its obnoxious"
check(Q in PM and Q in SRC["book"], "the header notes quote the user (2026-11-18)")
check("/petattack [pet" not in CODE and "OV.SPET" not in CODE and "smartPet" not in CODE and "spResync" not in CODE
      and "wc-spet" not in CODE and "OV.spCast" not in CODE and "OV.spMacro" not in CODE and "OV.atkPre" not in CODE
      and "petattack" not in CODE.replace('body = "/petattack"', "").replace("PETATTACK", ""),
      "no /petattack line, no OV.SPET / spCast / spMacro / atkPre, no smartPet hook, no spResync, no wc-spet in PadMap's code")
check(PM.count('body = "/petattack"') == 1, "the player's own Pet Attack command (a button they place) is untouched")
check('local function attackBody(name) return "#showtooltip\\n" .. attackLines("") .. "/cast " .. name end' in PM,
      "the saved \"DS Attack\" body is exactly as before")

# ---------------------------------------------------------------- (1) no word: the texts
W = world()
check(clean(W) == {}, "the world loads (a Hunter, a pet out, a hostile target), strict, clean", clean(W))
d = pf(W)
check(d is not None and d.get('cls') is True and d.get('on') is None and 'sp' not in d, "pf: a pet class, no word yet, no `sp`", d)
KEYS = ("wc-sa-17", "wc-sa-13", "wc-sa-18", "wc-sa-20", "wc-mt-29", "wc-mt-30", "wc-mt-32")
base = {k: hdr(W, k) for k in KEYS}
check(base["wc-sa-17"] == ATTACK_LINES and base["wc-sa-13"] == ATTACK_LINES and base["wc-sa-18"] is None and base["wc-sa-20"] is None,
      "no word: the start-attack lines as before (harmful slots only, the pet's Bite never)", base)
check(base["wc-mt-29"] == "#showtooltip\n/cast Auto Shot" and base["wc-mt-30"] == "/cast Healing Wave" and base["wc-mt-32"] == "/startattack",
      "no word: the player's macros and our Start Attack as their own texts", base)


def same_routes(L, tag):
    now = {k: hdr(L, k) for k in KEYS}
    check(now == base, tag + ": every router text byte-identical to no word", now)
    r = press(L, "PAD3", lt=True)
    check(lines(r) == [SOFT] + SA + ["GPLeft5+", "GPLeft5-"], tag + ": LT + X (Auto Shot): the start-attack lines, the game's button, no pet line",
          (lines(r), r['clicks']))
    r = press(L, "PADDLEFT", lt=True)
    check(SPET not in lines(r), tag + ": LT + D-pad left (Arcane Shot): no pet line", lines(r))
    r = press(L, "PAD3", rt=True)
    check(lines(r) == ["#showtooltip", "/cast Auto Shot"], tag + ": RT + X (the player's macro): its own text", lines(r))
    r = press(L, "PAD1", rt=True)
    check(lines(r) == ["/startattack"], tag + ": RT + A (our Start Attack): /startattack alone", lines(r))
    r = press(L, "PAD3")
    check(SPET not in lines(r), tag + ": X (Interact) never sends the pet", (r['how'], lines(r)))
    check(L.eval('MACROS[1][2]') == "#showtooltip\n/cast Auto Shot", tag + ": the saved macro untouched")


same_routes(W, "no word")

# ---------------------------------------------------------------- (2) 37: recall on, Home free
n0 = nsent(W)
tell(W, 37)
d = pf(W, n0)
check(d == {"on": True, "bf": True, "cls": True, "pet": True, "b": True}, "37: pf on / bf / B carried, no `sp`", d)
same_routes(W, "37")
r = press(W, "PAD2")
check(lines(r)[:1] == ['/petfollow'], "37: B (the pet at something): /petfollow first, as before", lines(r))
check(clean(W) == {}, "37: no spied call (no macro / binding / CVar saved), no error", clean(W))

# ---------------------------------------------------------------- (2b) 0: recall on, Home busy (was Smart pet's "attack only")
H = world()
n0 = nsent(H)
tell(H, 0)
d = pf(H, n0)
check(d == {"on": True, "cls": True, "pet": True}, "0: pf on, no bf, B not carried, no `sp`", d)
check(H.eval('BIND["PAD2"]') != OURS_B, "0: B is not ours out of combat (Home's own B closes its module)", H.eval('BIND["PAD2"]'))
same_routes(H, "0 (an old 'attack only' word)")
H.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(0.5)')
r = press(H, "PAD3", lt=True)
check(SPET not in lines(r), "0, in a fight: LT + X never sends the pet", lines(r))
r = press(H, "PAD2")
check(lines(r) == ['/petfollow', '/stopattack'], "0, in a fight: the first B (the give-back) recalls the pet (m173, as before)", lines(r))
H.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
settle(H)
check(clean(H) == {}, "0: clean", clean(H))

# ---------------------------------------------------------------- (3) a fight with a face's fight spell: no pet line
F = world(pre=FACE)
fm0 = F.eval('WowCompanionCombatFace5.a["wc-fight-macro"]')
tell(F, 37)
fm = F.eval('WowCompanionCombatFace5.a["wc-fight-macro"]')
check(fm == fm0 == ATTACK_LINES + "/cast Lightning Bolt", "X's fight spell (harmful): the m171 text, no pet line", (fm0, fm))
F.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(0.5)')
r = press(F, "PAD3")
check(lines(r) == [SOFT] + SA + ["/cast Lightning Bolt"], "in the fight, X: soft target, start attack, the cast (no /petattack)", lines(r))
tell(F, 36)
F.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
settle(F)
check(lst(F, 'VIOL') == [] and F.eval('WowCompanionCombatFace5.a["wc-fight-macro"]') == fm0, "36 in the fight, then its end: unchanged texts",
      (lst(F, 'VIOL'), F.eval('WowCompanionCombatFace5.a["wc-fight-macro"]')))
check(clean(F) == {}, "the fight: no lockdown breach, no spied call, no snippet error", clean(F))

# ---------------------------------------------------------------- (4) the old hooks are gone; a class with no pet: nothing
pm = 'WowCompanion.padMap'
check(W.eval(pm + '.smartPetCast') is None and W.eval(pm + '.smartPetCmd') is None and W.eval(pm + '.smartPetOn') is None
      and W.eval(pm + '.smartPet') is None and W.eval('WowCompanion.spResync') is None and W.eval('type(' + pm + '.petRecall)') == "function",
      "WC.padMap: petRecall only (no smartPet / smartPetCast / smartPetCmd / smartPetOn), no WC.spResync")
X = world(cls="WARRIOR", pet=False)
tell(X, 37)
r = press(X, "PAD3", lt=True)
check(SPET not in lines(r) and hdr(X, "wc-sa-17") == ATTACK_LINES, "a Warrior told on anyway: its presses never send a pet", lines(r))

for L in (W, F, H, X):
    check(L.eval('#SPY') == 0 and L.eval('#SNIPERR') == 0, "no spied call, no snippet error", (L.eval('SPY[1]'), L.eval('SNIPERR[1]')))

print("%d passed, %d failed" % (passes, fails))
print("ALL PASS" if fails == 0 else "FAILURES")
sys.exit(1 if fails else 0)
