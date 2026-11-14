"""A STANCE-COVERED BUTTON BINDS TO THE ACTIVE STANCE'S BAR (Book b49, PadMap m142, PadTabCtl p44), off-device: the REAL add-on
files under tools/wowstub.py's stubbed game.

    python tools/test_stance_bind.py

Issue #179 (1.0.25): "buttons do not stay mapped on ZR +FACE buttons". A Warrior (always in a stance) bound Charge on ZR + B, page 1;
the game's GamepadStanceBarOverride = 3 lays the Battle Stance bar over the RT set on page 1, the Bind box's form defaulted to the
caster, and the plain RT slot storageFor(1, 3, 7) = 199 took the spell while the game, the router and Home press / show the stance
bar's slot. The user's ruling ("Build it"): a bind on a stance-covered button goes to the active stance's bar by default, in the
Spellbook and the Controller room; the caster ("Normal") stays pickable on purpose. Proven here:
  a Warrior, CVar 3, Battle Stance, stance first slot 73, no form picked: ZR + B page 1 -> PlaceAction(79), not 199 (slotSrc stance);
    the same with nothing kept and no PadMap learn -> the game's own first slot (slotSrc game); the box reports bform = the stance;
  b the caster picked on purpose (bindForm 0) -> 199;
  c a Druid in caster form (GetShapeshiftForm 0) -> 199;
  d the stance bar on LT (CVar 2): RT -> 199, LT -> the stance slot;
  e page 2 RT with CVar 3 -> the formula slot;
  f the game's word nil (no stance bar shown): the default keeps the caster path (199); a form PICKED whose bar is not kept ->
    "form-unknown", no PlaceAction;
  g in combat -> refused before any placement;
  h the Controller room: the form defaults to the active one (OV.fe), a put on (1, 3, 7) goes to first + 6, the caster picked -> 199;
  i B's undo after a stance bind (ZR + A: without capture v4 a bare B is the box's way back) gives the stance slot its old spell back;
  j (review) a form with NO stance bar (the game's bonus bar down: Shaman Ghost Wolf, Druid Travel Form, a Paladin's aura) with CVar 3:
    the bind goes to 199, nothing learned, no bform, the room's (1, 3, 7) is the plain slot;
  k (review) a fresh room open forgets an explicit FORM pick too (Defensive picked, leave, reopen in Battle Stance -> Battle's bar);
  l (review) a stance change with the room open: the room follows before the next input (a put lands on the new stance's slot).
Needs lupa. Exit code 1 on any failure."""
import json
import os
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
fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


# the game's bar slots, the cursor road, the forms and the stance bar's Controller setting
EXTRA = r'''
CLASS = "WARRIOR"
SPELLNAME[100] = "Charge"; SPELLNAME[6673] = "Battle Shout"; SPELLNAME[772] = "Rend"
SPELLNAME[2457] = "Battle Stance"; SPELLNAME[71] = "Defensive Stance"
SPELLNAME[768] = "Cat Form"; SPELLNAME[5487] = "Bear Form"; SPELLNAME[783] = "Travel Form"; SPELLNAME[2645] = "Ghost Wolf"
SPELLNAME[465] = "Devotion Aura"
BONUS = true
C_ActionBar = { HasBonusActionBar = function() return BONUS end }
BOOK = { 100, 6673, 772 }
CVARS = { GamepadStanceBarOverride = "3" }
C_CVar = { GetCVar = function(k) return CVARS[k] end }
FORMS = { { id = 2457, icon = 132349 }, { id = 71, icon = 132341 } }
FORM = 1
function GetNumShapeshiftForms() return #FORMS end
function GetShapeshiftForm() return FORM end
function GetShapeshiftFormInfo(i) local f = FORMS[i]; if f then return f.icon, i == FORM, true, f.id end end
STFIRST = 73
C_GamepadUI = {
    GetFirstGamepadActionBarStorageSlotIndexForActiveStance = function() return STFIRST end,
    GetFirstGamepadActionStorageSlotIndex = function() return 181 end,
    IsValidGamepadActionStorageSlotIndex = function() return true end,
}
Constants = { GamepadActionBarConstants = { NUM_SLOTS_PER_GAMEPAD_ACTION_BAR = 8,
    NUM_PAGEABLE_SLOTS_PER_GAMEPAD_ACTION_BAR_PAGE_UNIT_STANDARD_PAGE = 28, NUM_RESERVED_SLOTS_PER_GAMEPAD_ACTION_BAR_PAGE_UNIT = 4 } }
PAGE = 1
GamepadMainActionBarFrame.PageUnit.GetCurrentPage = function() return PAGE end
ACTIONINFO = {}
function GetActionInfo(s) local a = ACTIONINFO[s]; if a then return a[1], a[2] end end
function GetActionTexture(s) local a = ACTIONINFO[s]; return a and 130000 + a[2] or nil end
CURSOR = nil
PLACED = {}
function GetCursorInfo() if CURSOR then return CURSOR[1], CURSOR[2] end end
function ClearCursor() CURSOR = nil end
function PlaceAction(s) PLACED[#PLACED + 1] = s; local old = ACTIONINFO[s]; ACTIONINFO[s] = CURSOR; CURSOR = old end
function PickupAction(s) CURSOR = ACTIONINFO[s]; ACTIONINFO[s] = nil end
C_Spell.PickupSpell = function(id) CURSOR = { "spell", id } end
C_SpellBook = { GetSpellBookItemInfo = function(slot) local id = BOOK[slot]; return id and { spellID = id } or nil end,
                PickupSpellBookItem = function(slot) local id = BOOK[slot]; if id then CURSOR = { "spell", id } end end }
PADSTATE = {}
function GetGamePadState() return { buttons = PADSTATE } end     -- addon code's read of the controller (Book padSet)
function hold(which) PADSTATE = {}; if which == "LT" or which == "both" then PADSTATE[LT_I] = true end
    if which == "RT" or which == "both" then PADSTATE[RT_I] = true end end
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
    L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
    L.execute('BK = WowCompanion._spellsBook; BK.ctl.on = true; BK.ctl.pg = "book"')
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


def bind(L, trig, key="PAD2", form='nil'):
    """start the Bind box on Charge (book slot 1), maybe pick a form, hold the trigger(s) and press; the last bind ack and place"""
    n0 = int(L.eval('#PLACED'))
    L.execute('BK.startBind({ t = 1, slot = 1, id = 100 })')
    if form != 'nil':
        L.execute('BK.ctl.bindForm = %s' % form)
    L.execute('hold("%s")' % trig)
    why = L.eval('BK.bindPress("%s")' % key)
    acks = [d for d in docs(L, 'spellsack') if d.get('op') == 'bind']
    placed = [L.eval('PLACED[%d]' % i) for i in range(n0 + 1, int(L.eval('#PLACED')) + 1)]
    return why, (acks[-1] if acks else {}), placed


def errs(L):
    return list(L.eval('ERRS').values()) if L.eval('ERRS') is not None else []


comp = lupa.LuaRuntime().eval('function(s, n) local f, e = loadstring(s, n); return e end')
for k in ("book", "padmap", "padtabctl"):
    e = comp(SRC[k], k)
    check(e is None, "Lua 5.1 compiles %s" % os.path.basename(F[k]), e or '')
check('local BV = "b61"' in SRC["book"] and 'local PV = "m160"' in SRC["padmap"] and 'local CV = "p54"' in SRC["padtabctl"],
      "the versions: Book b61 (b49 + the toolbar D-pad + rank up; Smart Range gone), PadMap m160, PadTabCtl p54 (issue #179)")
for k in ("book", "padmap", "padtabctl"):
    check("buttons do not stay mapped on ZR +FACE buttons" in SRC[k], "%s's header quotes the user's report" % os.path.basename(F[k]))

# ================================================================ a. the Warrior's Charge on ZR + B goes to Battle Stance's bar
W = boot()
check(errs(W) == [], "the world loads (a Warrior in Battle Stance, the stance bar over RT on page 1)", errs(W)[:2])
check(W.eval('BK.storageFor(1, 3, 7)') == 199, "the plain RT formula slot for (1, 3, 7) is 199 (the issue's ack)", W.eval('BK.storageFor(1, 3, 7)'))
W.execute('BK.startBind({ t = 1, slot = 1, id = 100 })')
check(W.eval('BK.ctl.bindForm') is None, "a fresh Bind box: no form picked yet (nil, not 0)")
ctl = docs(W, 'spellsctl')[-1]['ctl']
check(ctl.get('bform') == 2457, "the box reports bform = Battle Stance (the app's form row lights the active stance)", ctl.get('bform'))
why, ack, placed = bind(W, "RT")
check(why is None and ack.get('ok') is True and placed == [79],
      "ZR + B page 1, nothing picked: PlaceAction(79) = the stance bar's slot (73 + 6), not 199", (why, placed, ack.get('why')))
check(ack.get('pad', {}).get('s') == 79 and ack.get('probe', {}).get('slotSrc') == 'stance',
      "the ack: s = 79, probe.slotSrc = stance", (ack.get('pad'), ack.get('probe', {}).get('slotSrc')))
check(W.eval('ACTIONINFO[79][2]') == 100 and W.eval('ACTIONINFO[199]') is None, "Charge sits on slot 79; slot 199 untouched")
fs = W.eval('WowCompanionSpellsDB.formSlotsBy[WHO .. "-" .. REALM][2457]')
check(fs == 73, "the bind kept Battle Stance's first slot (PadMap formLearn)", fs)
# the game's own word when nothing is kept and PadMap cannot learn it
W.execute('WowCompanionSpellsDB.formSlotsBy = {}; SAVED_LEARN = WowCompanion.padMap.formLearn; WowCompanion.padMap.formLearn = nil')
why, ack, placed = bind(W, "RT", key="PAD1")
check(why is None and placed == [80] and ack.get('probe', {}).get('slotSrc') == 'game',
      "nothing kept, no learn: the game's ForActiveStance first slot (ZR + A -> 80, slotSrc game)", (why, placed, ack.get('probe', {}).get('slotSrc')))
W.execute('WowCompanion.padMap.formLearn = SAVED_LEARN')
# an explicit form picked (Defensive Stance's bar kept at 85) -> slotSrc form
W.execute('WowCompanionSpellsDB.formSlotsBy = { [WHO .. "-" .. REALM] = { [71] = 85 } }')
why, ack, placed = bind(W, "RT", form='71')
check(why is None and placed == [91] and ack.get('probe', {}).get('slotSrc') == 'form',
      "Defensive Stance picked on purpose: its own kept bar (85 + 6 = 91, slotSrc form)", (why, placed, ack.get('probe', {}).get('slotSrc')))
# Select cycles from the shown default: Battle Stance -> Defensive Stance -> the caster (0)
W.execute('BK.startBind({ t = 1, slot = 1, id = 100 }); BK.ctl.mode = "bind"; hold(nil)')
W.execute('BK.press("PADBACK")')
c1 = W.eval('BK.ctl.bindForm')
W.execute('BK.press("PADBACK")')
c2 = W.eval('BK.ctl.bindForm')
check(c1 == 71 and c2 == 0, "Select cycles from the default: Defensive Stance next, then the caster", (c1, c2))
W.execute('BK.ctl.mode = "book"; BK.ctl.bind = nil')
check(errs(W) == [], "a: no Lua error", errs(W)[:2])

# ================================================================ b. the caster picked on purpose
B = boot()
why, ack, placed = bind(B, "RT", form='0')
check(why is None and placed == [199] and ack.get('probe', {}).get('slotSrc') == 'formula',
      "b. caster picked (bindForm 0): the plain RT slot 199 (slotSrc formula)", (why, placed, ack.get('probe', {}).get('slotSrc')))
B.execute('BK.startBind({ t = 1, slot = 1, id = 100 }); BK.ctl.bindForm = 0')
B.execute('BK.ctl.bindForm = nil; hold(nil); BK.press("PADBACK"); BK.press("PADBACK")')   # Select: Defensive, then the caster
check(docs(B, 'spellsctl')[-1]['ctl'].get('bform') is None, "b. the box with the caster picked reports no bform (the class icon lit)",
      docs(B, 'spellsctl')[-1]['ctl'].get('bform'))

# ================================================================ c. a Druid in caster form
D = boot('FORMS = { { id = 768, icon = 132115 }, { id = 5487, icon = 132276 } }; FORM = 0; CLASS = "DRUID"')
why, ack, placed = bind(D, "RT")
check(why is None and placed == [199], "c. a Druid in caster form (GetShapeshiftForm 0): 199", (why, placed))
check(docs(D, 'spellsctl')[-1]['ctl'].get('bform') is None, "c. no default form reported")

# ================================================================ d. the stance bar on LT
L2 = boot('CVARS.GamepadStanceBarOverride = "2"')
why, ack, placed = bind(L2, "RT")
check(why is None and placed == [199], "d. stance bar on LT, a bind on RT: 199 (not the stance's set)", (why, placed))
why, ack, placed = bind(L2, "LT")
check(why is None and placed == [79] and ack.get('probe', {}).get('slotSrc') == 'stance',
      "d. a bind on LT + B: the stance slot 79", (why, placed, ack.get('probe', {}).get('slotSrc')))

# ================================================================ e. page 2
P2 = boot('PAGE = 2')
why, ack, placed = bind(P2, "RT")
want = P2.eval('BK.storageFor(2, 3, 7)')
check(why is None and placed == [want] and want != 79 and ack.get('probe', {}).get('slotSrc') == 'formula',
      "e. page 2 RT with CVar 3 (the stance bar is on page 1): the formula slot", (why, placed, want))

# ================================================================ f. nothing kept, no game word
U = boot('STFIRST = nil')
why, ack, placed = bind(U, "RT")
check(why is None and placed == [199] and ack.get('probe', {}).get('slotSrc') == 'formula',
      "f. the game's word nil (no stance bar shown), nothing picked: the caster path, 199", (why, placed, ack.get('probe', {}).get('slotSrc')))
why, ack, placed = bind(U, "RT", form='71')
check(why == 'unsure' and ack.get('ok') is False and ack.get('why') == 'form-unknown' and placed == [],
      "f. Defensive Stance PICKED, its bar not kept: form-unknown, nothing placed", (why, ack.get('why'), placed))

# ================================================================ g. in combat
G = boot()
G.execute('BK.startBind({ t = 1, slot = 1, id = 100 }); COMBAT = true; hold("RT")')
n0 = G.eval('#PLACED')
why = G.eval('BK.bindPress("PAD2")')
ack = [d for d in docs(G, 'spellsack') if d.get('op') == 'bind'][-1]
check(why == 'combat' and ack.get('why') == 'combat' and G.eval('#PLACED') == n0, "g. in combat: refused before any placement", (why, ack.get('why')))
G.execute('COMBAT = false')

# ================================================================ h. the Controller room
H = boot()
H.execute('WowCompanion.padTabCtl.enter("tabs")')
c = docs(H, 'padtabctl')[-1]['c']
check(c.get('view') == 'normal' and c.get('form') == 2457, "h. the room opens on WoW Defaults with Battle Stance's bar chosen", (c.get('view'), c.get('form')))
H.execute('WowCompanion.padMap.emit(true)')
m = docs(H, 'padmap')[-1]['m']
check(m.get('fe') == 2457, "h. PadMap's OV.fe follows (the document's fe)", m.get('fe'))
n0 = int(H.eval('#PLACED'))
ok = H.eval('WowCompanion.padMap.putSpell(1, 3, 7, 1)')
placed = [H.eval('PLACED[%d]' % i) for i in range(n0 + 1, int(H.eval('#PLACED')) + 1)]
check(ok is True and placed == [79], "h. a spell put on (1, 3, 7) goes to the stance slot first + 6 = 79 (the game's word, nothing kept)", (ok, placed))
H.execute('WowCompanion.padTabCtl.touchForm(0)')
c = docs(H, 'padtabctl')[-1]['c']
check(c.get('form') is None, "h. the caster picked by touch: no form", c.get('form'))
n0 = int(H.eval('#PLACED'))
ok = H.eval('WowCompanion.padMap.putSpell(1, 3, 7, 1)')
placed = [H.eval('PLACED[%d]' % i) for i in range(n0 + 1, int(H.eval('#PLACED')) + 1)]
check(ok is True and placed == [199], "h. the caster picked: the plain RT slot 199", (ok, placed))
H.execute('WowCompanion.padTabCtl.sync()')
check(docs(H, 'padtabctl')[-1]['c'].get('form') is None, "h. the explicit caster pick is kept (a later report does not re-default)")
H.execute('WowCompanion.padTabCtl.leave(); WowCompanion.padTabCtl.enter("tabs")')
check(docs(H, 'padtabctl')[-1]['c'].get('form') == 2457, "h. a fresh room open starts on the active stance again")
H.execute('WowCompanion.padTabCtl.touchTab(3)')
c = docs(H, 'padtabctl')[-1]['c']
check(c.get('view') == 'combat' and c.get('form') is None, "h. In combat with nothing picked keeps the caster (as before)", (c.get('view'), c.get('form')))
H.execute('FORM = 0; WowCompanion.padTabCtl.leave(); WowCompanion.padTabCtl.enter("tabs")')
check(docs(H, 'padtabctl')[-1]['c'].get('form') is None, "h. out of every stance: the caster's set")
check(errs(H) == [], "h: no Lua error", errs(H)[:2])

# ================================================================ i. B's undo restores the stance slot
I = boot()
I.execute('ACTIONINFO[80] = { "spell", 6673 }')
I.execute('BK.startBind({ t = 1, slot = 1, id = 100 }); hold("RT"); BK.press("PAD1")')
check(I.eval('BK.ctl.mode') == 'confirm' and I.eval('ACTIONINFO[80][2]') == 100, "i. the bind took on 80 (the box asks Keep / Undo)",
      (I.eval('BK.ctl.mode'), I.eval('ACTIONINFO[80] and ACTIONINFO[80][2]')))
I.execute('hold(nil); BK.press("PAD2")')
un = [d for d in docs(I, 'spellsack') if d.get('op') == 'unbind']
check(un and un[-1].get('ok') is True and I.eval('ACTIONINFO[80][2]') == 6673 and I.eval('ACTIONINFO[200]') is None,
      "i. B undoes it: Battle Shout back on 80, 200 untouched", (un[-1] if un else None, I.eval('ACTIONINFO[80] and ACTIONINFO[80][2]')))
check(errs(I) == [], "i: no Lua error", errs(I)[:2])

# ================================================================ j. forms with no stance bar (review)
def room_put(L):
    n0 = int(L.eval('#PLACED'))
    ok = L.eval('WowCompanion.padMap.putSpell(1, 3, 7, 1)')
    return ok, [L.eval('PLACED[%d]' % i) for i in range(n0 + 1, int(L.eval('#PLACED')) + 1)]


for label, pre in (
        ("Shaman Ghost Wolf (bonus bar down, the game still names a slot)",
         'CLASS = "SHAMAN"; FORMS = { { id = 2645, icon = 136095 } }; FORM = 1; BONUS = false; STFIRST = 73'),
        ("Druid Travel Form (bonus bar down, no slot named)",
         'CLASS = "DRUID"; FORMS = { { id = 768, icon = 132115 }, { id = 783, icon = 132144 } }; FORM = 2; BONUS = false; STFIRST = nil'),
        ("Paladin Devotion Aura (the global HasBonusActionBar false)",
         'CLASS = "PALADIN"; FORMS = { { id = 465, icon = 135893 } }; FORM = 1; C_ActionBar = {}; '
         'function HasBonusActionBar() return false end; STFIRST = 73')):
    J = boot(pre)
    J.execute('BK.startBind({ t = 1, slot = 1, id = 100 })')
    bf = docs(J, 'spellsctl')[-1]['ctl'].get('bform')
    why, ack, placed = bind(J, "RT")
    src = ack.get('probe', {}).get('slotSrc')
    check(why is None and placed == [199] and src in ('button', 'formula') and bf is None,
          "j. %s, CVar 3, ZR + B page 1: 199, slotSrc %s, no bform" % (label, src), (why, placed, src, bf))
    kept = J.eval('WowCompanionSpellsDB.formSlotsBy and WowCompanionSpellsDB.formSlotsBy[WHO .. "-" .. REALM] '
                  'and next(WowCompanionSpellsDB.formSlotsBy[WHO .. "-" .. REALM])')
    check(kept is None, "j. %s: no form slot learned" % label, kept)
    J.execute('WowCompanion.padTabCtl.enter("tabs")')
    c = docs(J, 'padtabctl')[-1]['c']
    ok, placed = room_put(J)
    check(c.get('form') is None and ok is True and placed == [199],
          "j. %s: the room keeps the caster's set, a put on (1, 3, 7) -> 199" % label, (c.get('form'), ok, placed))
    check(errs(J) == [], "j. %s: no Lua error" % label, errs(J)[:2])

# ================================================================ k. a fresh open forgets a FORM pick (review)
K = boot('WowCompanionSpellsDB = WowCompanionSpellsDB or {}')
K.execute('WowCompanion.padTabCtl.enter("tabs"); WowCompanion.padTabCtl.touchForm(2)')
c1 = docs(K, 'padtabctl')[-1]['c'].get('form')
K.execute('WowCompanion.padTabCtl.leave(); WowCompanion.padTabCtl.enter("tabs"); WowCompanion.padMap.emit(true)')
c2 = docs(K, 'padtabctl')[-1]['c'].get('form')
fe = docs(K, 'padmap')[-1]['m'].get('fe')
check(c1 == 71 and c2 == 2457 and fe == 2457,
      "k. Defensive picked, leave, reopen in Battle Stance: Battle Stance's bar (form and OV.fe)", (c1, c2, fe))
check(errs(K) == [], "k: no Lua error", errs(K)[:2])

# ================================================================ l. a stance change with the room open (review)
M = boot()
M.execute('WowCompanion.padTabCtl.enter("tabs")')
M.execute('WowCompanionSpellsDB.formSlotsBy[WHO .. "-" .. REALM][71] = 85')
M.execute('FORM = 2; STFIRST = 85; fire("UPDATE_SHAPESHIFT_FORM"); runTimers(NOW + 1)')
c = docs(M, 'padtabctl')[-1]['c']
ok, placed = room_put(M)
check(c.get('form') == 71 and ok is True and placed == [91],
      "l. Battle -> Defensive with the room open: the room follows, a put on (1, 3, 7) -> 91", (c.get('form'), ok, placed))
check(errs(M) == [], "l: no Lua error", errs(M)[:2])

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
