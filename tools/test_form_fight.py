"""PER-FORM IN-COMBAT SETS ON EVERY LAYER (PadMap m204, PadTabCtl p64, Book b89, ActionBar nbar14), off-device: the REAL add-on files
(Safety, TapPad, Book, PadMap, PadTabCtl) under tools/wowstub.py's game, a Druid with two forms (Bear Form = form 1, Cat Form = form 2)
and the game's [form:N] macro condition.

    python -B tools/test_form_fight.py

The field report (a Druid, forwarded 2026-11-26): "I can assign per druid form bindings for *out of combat* ... However, in combat
bindings do not work. Regardless of which tab I'm on for each form changing them on one form changes it on everything", then "the per
form combat set is for the bare non trigger set. So per form Z+R stays the caster bar in combat. I tried assigning per form stuff to the
bare set and those bindings persisted in the combat keybindings page but still don't seem to appear when combat starts."

Proves:
  1 the bare set: a form's own In-combat spell on the D-pad and on X / Y / A is what the press casts in a fight, in THAT form only
    (another form / the caster keep theirs); Y with a target casts the form's spell (never the target radial in its place);
  2 a trigger layer (ZR = set 3, ZL = set 2, ZL + ZR = set 4): a form's own entry there, kept apart from the caster's and from every
    other form (formFightBy's set key), armed out of combat as the router's per-form text and cast by the press in a fight in that
    form; a form with no entry there keeps the caster's fight spell / the live slot's;
  3 the Controller room's In combat tab with a form chosen: a put / clear on ZR (and the bare set) changes ONLY that form's set;
    the caster's and the other form's stay; the room shows the chosen form's entry for every set;
  4 the documents: every form entry carries its set (`b`); a bare entry keeps no `b` change for older readers (b = 1);
  5 a form that changes IN the fight (Cat -> Bear) switches the press's spell with no secure write from insecure code;
  6 no trace: no SetBinding / SetCVar / macro write, no lockdown breach, no snippet error.
Needs lupa. Exit code 1 on any failure."""
import json
import os
import re
import sys

from lupa import lua51 as lupa

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import GAME, SRC, F  # noqa: E402
PARTY = open(os.path.join(ROOT, "tools", "test_party.py"), encoding="utf-8").read()
WORLD_HELPERS = re.search(r"\nWORLD_HELPERS = r'''(.*?)'''", PARTY, re.S).group(1)
fails = passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' and not ok else ''))
    if ok:
        passes += 1
    else:
        fails += 1


# the game's bar slots (storage = the bar's button ids: set 1 = 1-12, set 2 = 13-24, set 3 = 25-36, set 4 = 37-48), the attribute
# drivers ([combat] fight), the header's own bindings, a Druid's book and forms, and [form:N] in the macro conditions
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
function GetGamePadState() return { buttons = PADSTATE } end     -- addon code's read of the controller (Book's Bind box)
C_SpellBook = { GetSpellBookItemInfo = function(slot) local id = BOOK[slot]; return id and { spellID = id } or nil end }
CLASS = "DRUID"
SPELLNAME[5487] = "Bear Form"; SPELLNAME[768] = "Cat Form"
SPELLNAME[5221] = "Shred"; SPELLNAME[1082] = "Claw"; SPELLNAME[6807] = "Maul"; SPELLNAME[33917] = "Mangle"; SPELLNAME[5176] = "Wrath"
SPELLNAME[8921] = "Moonfire"
BOOK = { 5176, 5221, 1082, 6807, 33917, 8921 }      -- book slots 1-6
FORMS = { { id = 5487, icon = 132276 }, { id = 768, icon = 132115 } }
FORM = 0                                             -- 0 = caster; 1 = Bear Form, 2 = Cat Form (the game's stance bar order)
function GetNumShapeshiftForms() return #FORMS end
function GetShapeshiftForm() return FORM end
function GetShapeshiftFormInfo(i) local f = FORMS[i]; if f then return f.icon, i == FORM, true, f.id end end
-- [form:N] (the game's: the form index now; [form] / [form:0] none): the stub's parser plus that one condition
local baseParse = SecureCmdOptionParse
function SecureCmdOptionParse(s)
    s = s:gsub("^%s+", "")
    local cond, rest = s:match("^%[(.-)%]%s*(.*)$")
    if cond then
        local n = cond:match("^form:(%d+)$")
        if n then if tonumber(n) == FORM then return rest end return nil end
    end
    return baseParse(s)
end
'''
AFTER = r'''
WowCompanion.spellsStorageFor = function(page, set, pos) return (page - 1) * 48 + (set - 1) * 12 + pos end
ACTIONINFO[1] = { "spell", 5176 }               -- WoW Defaults: Wrath on the bare D-pad Left
local ctl = WowCompanion._spellsBook.ctl
ctl.croom, ctl.ctrlTab, ctl.onTabs = true, true, true
function pp(key) return WowCompanion.padTabCtl.press(key) end
function FT(slot) local t = (WowCompanionSpellsDB.fightBy or {})[WHO .. "-" .. REALM]; return t and t[slot] end
function FF(form, k) local t = (WowCompanionSpellsDB.formFightBy or {})[WHO .. "-" .. REALM]; t = t and t[form]; return t and t[k] end
function HDR(k) return rawget(_G, "WowCompanionFaceRouter").a[k] end
function fightOn() fire("PLAYER_REGEN_DISABLED"); COMBAT = true; driveAll() end
function fightOff() COMBAT = false; driveAll(); fire("PLAYER_REGEN_ENABLED"); runTimers(NOW + 1) end
function shift(n) FORM = n; driveAll(); if not COMBAT then fire("UPDATE_SHAPESHIFT_FORM"); runTimers(NOW + 1) end end
-- a hardware press of a key (its binding), both edges, with the triggers held as asked; the router's wc-kind / macrotext after the key-down
function key(k, lt, rt)
    trig(lt, rt)
    press(k, true)
    local a = BIND[k]
    local name = a and a:match("^CLICK ([%w_]+):")
    local b = name and rawget(_G, name)
    local kind, mt = b and b.a["wc-kind"], b and b.a["macrotext"]
    press(k, false)
    trig(false, false)
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
    L.execute('driveAll(); WowCompanion.padMap.emit(true); runTimers(NOW + 1)')
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


def clean(L, name):
    check(L.eval('#SPY') == 0 and L.eval('#VIOL') == 0 and L.eval('#SNIPERR') == 0 and list(L.eval('ERRS').values()) == [],
          name + ": no spied call (no trace), no lockdown breach, no snippet error",
          (L.eval('SPY[1]'), L.eval('VIOL[1]'), L.eval('SNIPERR[1]'), list(L.eval('ERRS').values())[:2]))


def kd(L, k, lt=False, rt=False):
    r = L.eval('key')(k, lt, rt)
    return tuple(r)


pm = 'WowCompanion.padMap'
BEAR, CAT = 5487, 768
# ================================================================ 0. the world
W = boot()
check(list(W.eval('ERRS').values()) == [] and W.eval('#SNIPERR') == 0, "0. the world loads", list(W.eval('ERRS').values())[:2])
check([f['id'] for f in W.eval(pm + '.forms()').values()] == [BEAR, CAT], "0. the Druid's forms: Bear Form (1), Cat Form (2)")

# ================================================================ 1. the bare set in a fight
# the caster's D-pad Left fights with Moonfire (book slot 6); Cat Form's own D-pad Left = Shred (2), its X (5) = Claw (3), its Y (6) =
# Mangle (5), its A (8) = Maul (4); Bear Form has nothing of its own
check(W.eval(pm + '.putCombat(1, 1, 1, 6)') is True, "1. the caster's In-combat D-pad Left: Moonfire")
for pos, bs in ((1, 2), (5, 3), (6, 5), (8, 4)):
    check(W.eval(pm + '.putCombatForm(%d, %d, %d)' % (CAT, pos, bs)) is True, "1. Cat Form's own bare %d: book slot %d" % (pos, bs))
# the caster's Y has an In-combat spell too (Wrath, book slot 1): its router button opens the target radial on a bare Y with a target
# whenever no fight spell is cast (wc-tradial, armed out of combat)
check(W.eval(pm + '.putCombatFace(6, 1)') is True and W.eval('router(6).a["wc-tradial"]') is True,
      "1. the caster's Y fights with Wrath; Y's target radial is armed", W.eval('router(6).a["wc-tradial"]'))
W.execute('UNITS.target = { alive = true, foe = true, name = "Boar" }')
W.execute('shift(2); fightOn()')
check(W.eval('HDR("wc-form")') == 2, "1. the header's form state (the [form:N] driver): 2", W.eval('HDR("wc-form")'))
a, kind, mt = kd(W, "PADDLEFT")
check(kind == "slotfight" and (mt or '').endswith("/cast Shred"), "1. Cat Form, in a fight: D-pad Left casts Shred", (a, kind, mt))
a, kind, mt = kd(W, "PAD3")
check(kind == "slotfight" and (mt or '').endswith("/cast Claw"), "1. Cat Form, in a fight: X casts Claw", (a, kind, mt))
a, kind, mt = kd(W, "PAD4")
check(kind == "slotfight" and (mt or '').endswith("/cast Mangle"), "1. Cat Form, in a fight, a target: Y casts Mangle (not the target radial)",
      (a, kind, mt))
a, kind, mt = kd(W, "PAD1")
check(kind == "slotfight" and (mt or '').endswith("/cast Maul"), "1. Cat Form, in a fight: A casts Maul", (a, kind, mt))
W.execute('shift(1)')
a, kind, mt = kd(W, "PADDLEFT")
check(kind == "slotfight" and (mt or '').endswith("/cast Moonfire"), "1/5. shifted to Bear Form IN the fight: D-pad Left is the caster's Moonfire",
      (a, kind, mt))
W.execute('shift(2)')
a, kind, mt = kd(W, "PADDLEFT")
check(kind == "slotfight" and (mt or '').endswith("/cast Shred"), "5. back to Cat Form in the fight: Shred again", (a, kind, mt))
W.execute('fightOff(); shift(0)')
clean(W, "1")

# ================================================================ 2. a trigger layer: ZR (set 3), ZL (set 2), ZL + ZR (set 4)
# the caster's ZR + X (set 3, position 5 = slot 29) fights with Moonfire; Cat Form's own ZR + X = Shred, ZL + A (set 2, 8) = Maul,
# ZL + ZR + D-pad Up (set 4, 2) = Claw; Bear Form's own ZR + X = Mangle
check(W.eval(pm + '.putCombat(1, 3, 5, 6)') is True and W.eval('FT(29) and FT(29).n') == "Moonfire", "2. the caster's ZR + X: Moonfire (slot 29)")
for form, st, pos, bs, what in ((CAT, 3, 5, 2, "Shred"), (CAT, 2, 8, 4, "Maul"), (CAT, 4, 2, 3, "Claw"), (BEAR, 3, 5, 5, "Mangle")):
    r = W.eval(pm + '.putCombatForm(%d, %d, %d, %d)' % (form, pos, bs, st))
    check(r is True, "2. %s's own set %d position %d: %s" % ("Cat Form" if form == CAT else "Bear Form", st, pos, what), r)
check(W.eval('FT(29) and FT(29).n') == "Moonfire", "2. the caster's ZR + X is untouched (Moonfire)", W.eval('FT(29) and FT(29).n'))
check(W.eval('FF(%d, 5) and FF(%d, 5).n' % (CAT, CAT)) == "Claw", "2. Cat Form's BARE X is untouched (Claw)",
      W.eval('FF(%d, 5) and FF(%d, 5).n' % (CAT, CAT)))
check(W.eval('FF(%d, 35) and FF(%d, 35).n' % (CAT, CAT)) == "Shred" and W.eval('FF(%d, 35) and FF(%d, 35).n' % (BEAR, BEAR)) == "Mangle",
      "2. kept per form and per set (formFightBy[form][set * 10 + position]: 35 = ZR + X)",
      (W.eval('FF(%d, 35)' % CAT), W.eval('FF(%d, 35)' % BEAR)))
check(W.eval('HDR("wc-ff-2-35")') == "/cast Shred" and W.eval('HDR("wc-ff-1-35")') == "/cast Mangle" and W.eval('HDR("wc-ff-2-28")') == "/cast Maul",
      "2. armed out of combat: the router's per-form texts by form index and set key",
      (W.eval('HDR("wc-ff-2-35")'), W.eval('HDR("wc-ff-1-35")'), W.eval('HDR("wc-ff-2-28")')))
check(W.eval('(%s.formFight(%d, 5, 3) or {}).n' % (pm, CAT)) == "Shred" and W.eval('(%s.formFight(%d, 5) or {}).n' % (pm, CAT)) == "Claw",
      "2. formFight(form, pos, set): ZR + X = Shred, the bare X (no set) = Claw")
W.execute('shift(2); fightOn()')
a, kind, mt = kd(W, "PAD3", rt=True)
check(kind == "slotfight" and (mt or '').endswith("/cast Shred"), "2. Cat Form, in a fight: ZR + X casts Shred", (a, kind, mt))
a, kind, mt = kd(W, "PAD1", lt=True)
check(kind == "slotfight" and (mt or '').endswith("/cast Maul"), "2. Cat Form, in a fight: ZL + A casts Maul", (a, kind, mt))
a, kind, mt = kd(W, "PADDUP", lt=True, rt=True)
check(kind == "slotfight" and (mt or '').endswith("/cast Claw"), "2. Cat Form, in a fight: ZL + ZR + D-pad Up casts Claw", (a, kind, mt))
a, kind, mt = kd(W, "PAD3")
check(kind == "slotfight" and (mt or '').endswith("/cast Claw"), "2. Cat Form, in a fight: the bare X is still the bare set's Claw", (a, kind, mt))
W.execute('shift(1)')
a, kind, mt = kd(W, "PAD3", rt=True)
check(kind == "slotfight" and (mt or '').endswith("/cast Mangle"), "2/5. Bear Form (shifted in the fight): ZR + X casts Mangle", (a, kind, mt))
a, kind, mt = kd(W, "PAD1", lt=True)
check(not (mt or '').endswith("/cast Maul"), "2. Bear Form: ZL + A is not Cat Form's Maul", (a, kind, mt))
W.execute('shift(0)')
a, kind, mt = kd(W, "PAD3", rt=True)
check(kind == "slotfight" and (mt or '').endswith("/cast Moonfire"), "2. the caster (out of every form) in the fight: ZR + X casts Moonfire",
      (a, kind, mt))
W.execute('fightOff()')
a, kind, mt = kd(W, "PAD3", rt=True)
check(kind != "slotfight", "2. out of combat: ZR + X is the button's own (no fight text)", (a, kind, mt))
check(W.eval(pm + '.clearCombatForm(%d, 5, 3)' % BEAR) is True and W.eval('FF(%d, 35)' % BEAR) is None and W.eval('HDR("wc-ff-1-35")') is None
      and W.eval('(FF(%d, 35) or {}).n' % CAT) == "Shred",
      "2. clearCombatForm(Bear Form, ZR + X): Bear Form's alone comes off (its fight text too), Cat Form's stays")
W.execute('shift(1); fightOn()')
a, kind, mt = kd(W, "PAD3", rt=True)
check(kind == "slotfight" and (mt or '').endswith("/cast Moonfire"), "2. Bear Form with nothing of its own on ZR + X: the caster's Moonfire",
      (a, kind, mt))
W.execute('fightOff(); shift(0)')
# a whole-button thing (the fight layer's command) on a form's trigger set; a binding there is refused (a form has no key of its own)
check(W.eval(pm + '.putFight(1, 3, 6, "command", "DSMAP", %d)' % CAT) is True and W.eval('HDR("wc-ff-2-36")') == "/click WowCompanionPadMap",
      "2. Open Map on Cat Form's ZR + Y (putFight with the form): its fight text", W.eval('HDR("wc-ff-2-36")'))
W.execute(pm + '.clearCombatForm(%d, 6, 3)' % CAT)
r = W.eval(pm + '.putFight(1, 3, 6, "bind", "JUMP", %d)' % CAT)
check(r[0] is False and r[1] == 'form', "2. a binding on a form's set is refused (form)", tuple(r))
check(W.eval(pm + '.putCombatForm(%d, 7, 2, 1)' % CAT) is not True and W.eval(pm + '.putCombatForm(%d, 7, 2, 3)' % CAT) is True,
      "2. B: never on the bare set (the game's Back), but ZR + B is a button like any other")
W.execute(pm + '.clearCombatForm(%d, 7, 3)' % CAT)
clean(W, "2")

# ================================================================ 3. the Controller room's In combat tab with a form chosen
from chooser_nav import Nav  # noqa: E402


def ctl(L):
    return docs(L, 'padtabctl')[-1]['c']


NAV = Nav(W, ctl)
W.execute('WowCompanion.padTabCtl.enter("buttons")')


def room(form_k, set_, pos):
    W.execute('local t = WowCompanion.padTabCtl.state(); t.view = "combat"; t.set = %d; t.pos = %d' % (set_, pos))
    W.execute('WowCompanion.padTabCtl.touchForm(%d); WowCompanion.padTabCtl.state().focus = "buttons"; WowCompanion.padTabCtl.sync()' % form_k)
    return ctl(W)


def put_row(ident, tab="spells"):
    W.execute('pp("PAD1")')
    NAV.tab(tab)
    got = NAV.goto(ident)
    W.execute('pp("PAD1")')
    if ctl(W).get('box'):
        W.execute('pp("PAD1")')
    return got


c = room(2, 3, 6)                                # form row k = 2: Cat Form; ZR + Y (set 3, position 6)
check(c['view'] == 'combat' and c.get('form') == CAT and c['shown'] == 3 and c['pos'] == 6, "3. the room: In combat, Cat Form, ZR + Y", c)
ft30 = W.eval('(FT(30) or {}).n')
got = put_row(8921)                              # Moonfire
check(got and W.eval('(FF(%d, 36) or {}).n' % CAT) == "Moonfire", "3. a spell put on ZR + Y with Cat Form chosen: Cat Form's own",
      (got, W.eval('(FF(%d, 36) or {}).n' % CAT), W.eval('(FT(30) or {}).n')))
check(W.eval('(FT(30) or {}).n') == ft30 and W.eval('FF(%d, 36)' % BEAR) is None,
      "3. ... the caster's ZR + Y (slot 30) and Bear Form's are untouched", (W.eval('(FT(30) or {}).n'), W.eval('FF(%d, 36)' % BEAR)))
c = room(1, 3, 6)                                # Bear Form
got = put_row(1082)                              # Claw
check(got and W.eval('(FF(%d, 36) or {}).n' % BEAR) == "Claw" and W.eval('(FF(%d, 36) or {}).n' % CAT) == "Moonfire",
      "3. Bear Form chosen: Claw is Bear Form's own ZR + Y; Cat Form's stays Moonfire",
      (W.eval('(FF(%d, 36) or {}).n' % BEAR), W.eval('(FF(%d, 36) or {}).n' % CAT)))
c = room(0, 3, 6)                                # the caster
got = put_row(5176)                              # Wrath
check(got and W.eval('(FT(30) or {}).n') == "Wrath" and W.eval('(FF(%d, 36) or {}).n' % BEAR) == "Claw"
      and W.eval('(FF(%d, 36) or {}).n' % CAT) == "Moonfire",
      "3. the caster chosen: the caster's own ZR + Y (slot 30); both forms keep theirs", W.eval('(FT(30) or {}).n'))
c = room(2, 3, 6)
h = W.eval('(function() local tc = WowCompanion.padTabCtl.state(); return tc.form end)()')
check(h == CAT, "3. the room's form row: Cat Form on ZR", h)
got = put_row('uncombat')                        # Clear combat spell with Cat Form chosen on ZR + Y
check(got and W.eval('FF(%d, 36)' % CAT) is None and W.eval('(FF(%d, 36) or {}).n' % BEAR) == "Claw" and W.eval('(FT(30) or {}).n') == "Wrath",
      "3. Clear combat spell on ZR + Y with Cat Form chosen: Cat Form's alone comes off",
      (got, W.eval('(FF(%d, 36) or {}).n' % CAT), W.eval('(FF(%d, 36) or {}).n' % BEAR), W.eval('(FT(30) or {}).n')))
c = room(2, 1, 3)                                # the bare set too (as before): Cat Form chosen, bare D-pad Right = Maul
got = put_row(6807)
check(got and W.eval('(FF(%d, 3) or {}).n' % CAT) == "Maul", "3. the bare set with Cat Form chosen: Cat Form's own (as before)",
      W.eval('(FF(%d, 3) or {}).n' % CAT))
W.execute('WowCompanion.padTabCtl.leave()')
clean(W, "3")

# ================================================================ 3b. the Spellbook's Bind box, its In combat tab, a form picked
W.execute('BK = WowCompanion._spellsBook; BK.ctl.on = true; BK.ctl.pg = "book"')


def bindbox(slot, sid, form, lt, rt, key):
    W.execute('BK.startBind({ t = 1, slot = %d, id = %d }); BK.ctl.bind.fight = true; BK.ctl.bindForm = %d' % (slot, sid, form))
    W.eval('trig')(lt, rt)
    why = W.eval('BK.bindPress("%s")' % key)
    W.execute('trig(false, false); BK.ctl.mode = "book"; BK.ctl.bind = nil; BK.ctl.undo = nil')
    return why


ft32 = W.eval('FT(32)')
why = bindbox(5, 33917, CAT, False, True, "PAD1")          # Mangle on ZR + A, Cat Form picked
check(why is None and W.eval('(FF(%d, 38) or {}).n' % CAT) == "Mangle" and W.eval('FT(32)') == ft32 and W.eval('FF(%d, 38)' % BEAR) is None,
      "3b. the Bind box, In combat, Cat Form picked, ZR + A: Cat Form's own (the caster's slot 32 and Bear Form's untouched)",
      (why, W.eval('(FF(%d, 38) or {}).n' % CAT), W.eval('FT(32)'), W.eval('FF(%d, 38)' % BEAR)))
why = bindbox(4, 6807, BEAR, True, False, "PADDLEFT")      # Maul on ZL + D-pad Left, Bear Form picked
check(why is None and W.eval('(FF(%d, 21) or {}).n' % BEAR) == "Maul" and W.eval('FF(%d, 21)' % CAT) is None,
      "3b. Bear Form picked, ZL + D-pad Left: Bear Form's own (key 21)", (why, W.eval('(FF(%d, 21) or {}).n' % BEAR)))
why = bindbox(5, 33917, 0, False, True, "PAD1")            # the caster picked
check(why is None and W.eval('(FT(32) or {}).n') == "Mangle" and W.eval('(FF(%d, 38) or {}).n' % CAT) == "Mangle",
      "3b. the caster picked, ZR + A: the caster's own fight spell (slot 32)", (why, W.eval('(FT(32) or {}).n')))
clean(W, "3b")

# ================================================================ 4. the documents
W.execute('WowCompanion.padMap.emit(true)')
ff = docs(W, 'padmap')[-1]['m'].get('ff') or []
z = [d for d in ff if d['f'] == CAT and d.get('b') == 3 and d['p'] == 5]
check(z and z[0].get('n') == 'Shred', "4. the padmap's ff: Cat Form's ZR + X carries b = 3, p = 5", ff)
z = [d for d in ff if d['f'] == CAT and d['p'] == 5 and d.get('b', 1) == 1]
check(z and z[0].get('n') == 'Claw', "4. Cat Form's bare X: b = 1 (or none), p = 5", ff)
check(all(1 <= d['p'] <= 8 for d in ff), "4. every entry's p is a position 1-8 (never the store's set key)", [d['p'] for d in ff])
clean(W, "4")

print("\n%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
