"""SMART PET (PadMap m172, Book b67), off-device: the REAL add-on files (Safety, TapPad, Map, Quests, Hub, Interact + Dialogs, Book,
PadMap, PadTabCtl) under tools/wowstub.py's game with STRICT laid over it (the lockdown, the press model, the restricted snippets).

    python -B tools/test_smart_pet.py

The user, 2026-11-17: "The pet follow on B thing works, but its not attacking the next thing that I target and attack. I feel like maybe it
needs to be a full smart pet feature where it stops on b and then starts attacking whatever that I target and start attack on"; the picks
"Attack + harmful spells" and "Off by default"; then "to be clear I think this should just be a smart pet feature that includes both of
those settings in one". ONE word (padtab 39 then 36 off / 37 on with Home free / 0 on with Home busy) and both halves:
  B calls the attacking pet back (m165 / m171, test_b_pet_follow.py) and the pet attacks your target when you start attacking or cast a
  harmful spell: the game's secure "/petattack [pet,harm,nodead]" in the press's own text, after the soft-enemy target line for a spell.

Proves: (0) the versions and notes; (1) before any word / told off: every route's text is byte-identical to the m171 texts; (2) on: a
harmful spell on a layer (start attack on: soft target, /petattack, start attack, re-arm, the game's button), Auto Shot the same, X's
Interact routed with /petattack first, the player's own harmful macro (after its #showtooltip), our Start Attack command, a face's fight
spell in a fight, a fight-layer spell; (3) helpful spells and the pet's own spells never; (4) start attack off: the pet's line alone after
the soft target; (5) B: /petfollow as before (37), and 0 = the attack half only (B the game's own); (6) a word in a fight waits for its
end (nothing set in the lockdown); (7) the touch cells' text (WC.padMap.smartPetCast / smartPetCmd); (8) a non-pet class: nothing; the
saved "DS Attack" body is unchanged; no spied call (no macro, binding or CVar saved). Needs lupa. Exit code 1 on any failure."""
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
    """36 = off, 37 = on with Home free (B too), 0 = on with Home busy (the attack half only)"""
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
SP_LINES = "/target [noexists][dead] softenemy\n/petattack [pet,harm,nodead]\n/startattack [harm,nodead]\n/click [harm,nodead] WowCompanionFaceRearm\n"
OURS_B = "CLICK WowCompanionFaceB:LeftButton"

# ---------------------------------------------------------------- (0) the versions, the notes
check('local PV = "m172"' in SRC["padmap"] and 'local BV = "b67"' in SRC["book"], "PadMap m172, Book b67")
check("I feel like maybe it needs to be a full smart pet feature where it stops on b and then starts attacking whatever that I target and"
      " start attack on" in SRC["padmap"]
      and "to be clear I think this should just be a smart pet feature that includes both of those settings in one" in SRC["padmap"]
      and "to be clear I think this should just be a smart pet feature that includes both of those settings in one" in SRC["book"],
      "the header notes quote the user")
check(SRC["padmap"].count('OV.SPET = "/petattack [pet,harm,nodead]\\n"') == 1 and "CastPetAction" not in SRC["padmap"]
      and "PetAttack()" not in SRC["padmap"],
      "the pet's attack is the game's secure /petattack in macro text only (no protected call from our code)")
check('local function attackBody(name) return "#showtooltip\\n" .. attackLines("") .. "/cast " .. name end' in SRC["padmap"],
      "the saved \"DS Attack\" body is exactly as before (Smart pet is runtime text only)")

# ---------------------------------------------------------------- (1) no word: every text exactly as m171
W = world()
check(clean(W) == {}, "the world loads (a Hunter, a pet out, a hostile target), strict, clean", clean(W))
d = pf(W)
check(d is not None and d.get('cls') is True and d.get('on') is None and d.get('sp') is None, "pf: a pet class, no word yet (Smart pet off)", d)
base = {k: hdr(W, k) for k in ("wc-sa-17", "wc-sa-13", "wc-sa-18", "wc-sa-20", "wc-mt-29", "wc-mt-30", "wc-mt-32")}
check(base["wc-sa-17"] == ATTACK_LINES and base["wc-sa-13"] == ATTACK_LINES and base["wc-sa-18"] is None and base["wc-sa-20"] is None,
      "no word: the start-attack lines as before (harmful slots only, the pet's Bite never)", base)
check(base["wc-mt-29"] == "#showtooltip\n/cast Auto Shot" and base["wc-mt-30"] == "/cast Healing Wave" and base["wc-mt-32"] == "/startattack",
      "no word: the player's macros and our Start Attack as their own texts", base)
r = press(W, "PAD3", lt=True)
check(lines(r) == [SOFT] + SA + ["GPLeft5+", "GPLeft5-"], "no word: LT + X (Auto Shot): the start-attack lines, then the game's button", (lines(r), r['clicks']))
check(SPET not in lines(r), "  no /petattack", lines(r))
r = press(W, "PAD3")
check(SPET not in lines(r), "no word: X (Interact) never sends the pet", lines(r))

# ---------------------------------------------------------------- (2) on (37): the pet goes on every attack route
n0 = nsent(W)
tell(W, 37)
d = pf(W, n0)
check(d is not None and d.get('on') is True and d.get('bf') is True and d.get('sp') is True and d.get('b') is True,
      "37: Smart pet on, Home free (pf on / bf / sp, B carried)", d)
check(hdr(W, "wc-sa-17") == SP_LINES and hdr(W, "wc-sa-13") == SP_LINES,
      "the harmful slots' lines: soft target, /petattack, start attack, re-arm", (hdr(W, "wc-sa-17"), hdr(W, "wc-sa-13")))
check(hdr(W, "wc-sa-18") is None and hdr(W, "wc-sa-20") is None, "a helpful spell, the pet's own Bite: no lines", (hdr(W, "wc-sa-18"), hdr(W, "wc-sa-20")))
r = press(W, "PAD3", lt=True)
check(lines(r) == [SOFT, SPET] + SA + ["GPLeft5+", "GPLeft5-"], "LT + X (Auto Shot): the soft target, the pet's attack, start attack, then the game's button",
      (lines(r), r['clicks']))
check(lines(r)[0] != SPET and lines(r)[1] == SPET, "  the soft enemy is targeted BEFORE /petattack (a new mob: the pet goes on the first press)", lines(r))
r = press(W, "PADDLEFT", lt=True)
check(lines(r)[:2] == [SOFT, SPET] and clicked(r, "GPLeft1"), "LT + D-pad left (Arcane Shot): the same", (lines(r), r['clicks']))
r = press(W, "PAD4", lt=True)
check(SPET not in lines(r), "LT + Y (Healing Wave, helpful): never", lines(r))
r = press(W, "PAD1", lt=True)
check(SPET not in lines(r), "LT + A (the pet's Bite: the pet's own attack): never", lines(r))
check(hdr(W, "wc-mt-29") == "#showtooltip\n" + SOFT + "\n" + SPET + "\n/cast Auto Shot",
      "the player's harmful macro: the pet's line after #showtooltip (the text the router runs; the macro itself unchanged)", hdr(W, "wc-mt-29"))
check(W.eval('MACROS[1][2]') == "#showtooltip\n/cast Auto Shot", "  the saved macro is untouched", W.eval('MACROS[1][2]'))
check(hdr(W, "wc-mt-30") == "/cast Healing Wave", "the player's helpful macro: as it is", hdr(W, "wc-mt-30"))
r = press(W, "PAD3", rt=True)
check(lines(r) == ["#showtooltip", SOFT, SPET, "/cast Auto Shot"], "RT + X (the player's macro): the pet first, then its own cast", lines(r))
r = press(W, "PAD4", rt=True)
check(SPET not in lines(r), "RT + Y (the helpful macro, not routed: the game's own button): never", lines(r))
check(hdr(W, "wc-mt-32") == SPET + "\n/startattack", "our Start Attack command: /petattack first", hdr(W, "wc-mt-32"))
r = press(W, "PAD1", rt=True)
check(lines(r) == [SPET, "/startattack"], "RT + A (Start Attack): the pet's attack, then the player's", lines(r))
r = press(W, "PAD3")
check(r['how'] == 'click' and lines(r)[:1] == [SPET] and clicked(r, "GPTop5"),
      "X (Forever's Interact: on a foe an attack), routed with Smart pet on: /petattack first, then the game's Interact", (r['how'], lines(r), r['clicks']))
r = press(W, "PAD2")
check(lines(r)[:1] == ['/petfollow'], "B (37, the pet at something): /petfollow first, as before", lines(r))
check(clean(W) == {}, "on: no spied call (no macro / binding / CVar saved), no error", clean(W))

# ---------------------------------------------------------------- (3) a fight: a face's fight spell, the fight layer
F = world(pre=FACE)
tell(F, 37)
fm = F.eval('WowCompanionCombatFace5.a["wc-fight-macro"]')
check(fm == SP_LINES + "/cast Lightning Bolt", "X's fight spell (Lightning Bolt, harmful): the pet's line in its fight text", fm)
F.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(0.5)')
r = press(F, "PAD3")
check(lines(r) == [SOFT, SPET] + SA + ["/cast Lightning Bolt"], "in the fight, X: soft target, /petattack, start attack, the cast", lines(r))
r = press(F, "PAD3", lt=True)
check(lines(r)[:2] == [SOFT, SPET], "in the fight, LT + X (Auto Shot): the pet's attack too", lines(r))
# a word told in the fight: nothing set in the lockdown, the texts follow at its end
n0 = nsent(F)
tell(F, 36)
check(lst(F, 'VIOL') == [] and F.eval('WowCompanionCombatFace5.a["wc-fight-macro"]') == fm,
      "36 told in the fight: nothing set in the lockdown (the texts stay until its end)", (lst(F, 'VIOL'), F.eval('WowCompanionCombatFace5.a["wc-fight-macro"]')))
F.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
settle(F)
check(F.eval('WowCompanionCombatFace5.a["wc-fight-macro"]') == ATTACK_LINES + "/cast Lightning Bolt" and hdr(F, "wc-sa-17") == ATTACK_LINES,
      "the fight's end: Smart pet's line gone everywhere (the m171 texts again)", (F.eval('WowCompanionCombatFace5.a["wc-fight-macro"]'), hdr(F, "wc-sa-17")))
check(clean(F) == {}, "the fight: no lockdown breach, no spied call, no snippet error", clean(F))

# ---------------------------------------------------------------- (4) off again: byte-identical; start attack off: the pet's line alone
n0 = nsent(W)
tell(W, 36)
d = pf(W, n0)
now = {k: hdr(W, k) for k in base}
check(d.get('on') is None and d.get('sp') is None and now == base, "36: off - every text byte-identical to before the word", (d, now))
r = press(W, "PAD3", lt=True)
check(lines(r) == [SOFT] + SA + ["GPLeft5+", "GPLeft5-"], "off: LT + X as before", lines(r))
r = press(W, "PAD3")
check(SPET not in lines(r), "off: X as before (no /petattack)", lines(r))
O = world(pre='WowCompanionSpellsDB.attackBy = { [WHO .. "-" .. REALM] = { on = false, chosen = true, slots = {} } }\n')
check(O.eval('rawget(_G, "WowCompanionFaceRouter").a["wc-sa-17"]') is None, "start attack off, no word: no lines (as before)",
      O.eval('rawget(_G, "WowCompanionFaceRouter").a["wc-sa-17"]'))
tell(O, 37)
check(hdr(O, "wc-sa-17") == SOFT + "\n" + SPET + "\n", "start attack off, Smart pet on: the soft target + the pet's attack only (no start attack)",
      hdr(O, "wc-sa-17"))
r = press(O, "PAD3", lt=True)
check(lines(r) == [SOFT, SPET, "GPLeft5+", "GPLeft5-"], "  LT + X: the pet goes, then the game's own Auto Shot", (lines(r), r['clicks']))
check(clean(O) == {}, "start attack off: clean", clean(O))

# ---------------------------------------------------------------- (5) 0 = on with Home busy: the attack half, B not carried
H = world()
n0 = nsent(H)
tell(H, 0)
d = pf(H, n0)
check(d.get('on') is True and d.get('bf') is None and d.get('sp') is True and d.get('b') is None,
      "0: Smart pet on with Home busy (pf on / sp, no bf, B not carried)", d)
check(H.eval('BIND["PAD2"]') != OURS_B, "  B is not ours for Pet Follow (Home's own B closes its module)", H.eval('BIND["PAD2"]'))
r = press(H, "PAD3", lt=True)
check(lines(r)[:2] == [SOFT, SPET], "  the attack half still sends the pet", lines(r))
tell(H, 37)
check(H.eval('BIND["PAD2"]') == OURS_B, "37 after 0: B carried again", H.eval('BIND["PAD2"]'))
r = press(H, "PAD2")
check(lines(r)[:1] == ['/petfollow'], "  B calls the pet back", lines(r))
r = press(H, "PAD3", lt=True)
check(lines(r)[:2] == [SOFT, SPET], "  and the next attack sends it again (B stops it, the attack sends it)", lines(r))
check(clean(H) == {}, "0 / 37: clean", clean(H))

# ---------------------------------------------------------------- (6) no pet out: the texts carry it, the press's [pet] decides
N = world(pet=False)
tell(N, 37)
check(hdr(N, "wc-sa-17") == SP_LINES, "no pet out: the line is armed anyway (a pet summoned mid-fight: the [pet] conditional decides)", hdr(N, "wc-sa-17"))
check(clean(N) == {}, "no pet: clean", clean(N))

# ---------------------------------------------------------------- (7) the touch cells' text (ActionBar / Radial ask PadMap)
pm = 'WowCompanion.padMap'
check(W.eval(pm + '.smartPetCast(75)') is None, "off: a touch cell's Auto Shot is the plain spell cell (nil)", W.eval(pm + '.smartPetCast(75)'))
tell(W, 37)
check(W.eval(pm + '.smartPetCast(75)') == SOFT + "\n" + SPET + "\n/cast Auto Shot", "on: a touch cell's Auto Shot casts through Smart pet's text",
      W.eval(pm + '.smartPetCast(75)'))
check(W.eval(pm + '.smartPetCast(331)') is None, "  a helpful spell: the plain cell", W.eval(pm + '.smartPetCast(331)'))
W.execute('WowCompanion.spellsPetName = function(id) if id == 17253 then return "Bite" end end')
check(W.eval(pm + '.smartPetCast(17253)') is None, "  the pet's own spell: the plain cell", W.eval(pm + '.smartPetCast(17253)'))
check(W.eval(pm + '.smartPetCmd("STARTATTACK", "/startattack")') == SPET + "\n/startattack"
      and W.eval(pm + '.smartPetCmd("STOPATTACK", "/stopattack")') == "/stopattack", "the cast bar's Start Attack sends the pet; other commands as they are")
check(W.eval(pm + '.smartPetOn()') is True, "smartPetOn: true", W.eval(pm + '.smartPetOn()'))

# ---------------------------------------------------------------- (8) a class with no pet: nothing
X = world(cls="WARRIOR", pet=False)
tell(X, 37)
check(pf(X).get('sp') is None and hdr(X, "wc-sa-17") == ATTACK_LINES, "a Warrior told on anyway: no Smart pet (its texts as before)", (pf(X), hdr(X, "wc-sa-17")))
r = press(X, "PAD3", lt=True)
check(SPET not in lines(r), "  its presses never send a pet", lines(r))

for L in (W, F, O, H, N, X):
    check(L.eval('#SPY') == 0 and L.eval('#SNIPERR') == 0, "no spied call, no snippet error", (L.eval('SPY[1]'), L.eval('SNIPERR[1]')))

print("%d passed, %d failed" % (passes, fails))
print("ALL PASS" if fails == 0 else "FAILURES")
sys.exit(1 if fails else 0)
