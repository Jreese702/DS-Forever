"""THE SPELLBOOK'S BIND BOX ON THE BARE D-PAD AND THE TRIGGER SETS (Book b50), off-device: the REAL add-on files under
tools/wowstub.py's stubbed game.

    python tools/test_book_bind.py

Issues #18 / #20 (1.0.15): "Trying to bind an attack, doesnt show up on bottom screen and or the button display for attacks on the
buton layout"; a second player on #20: "When I disabled the DS shortcuts (dpad) it allowed me to bind abilities to the Dpad. But only
there. Any modifier key binding doesn't work." #31 (1.0.16): "i cant bind new spells to anything".
The D-pad half, reproduced here on 1.0.27's Book b49: with the DS WoW toolbar on (PadMap dpadWant, the default answer on Home's
prompt), a BARE D-pad press in the Bind box placed the spell on the game's plain D-pad slot, which the router's toolbar keys cover
(Chat / Radial / Map / Home bar) - the spell never cast and Home drew the toolbar there, not the spell. The Controller room already
refused that place (PadTabCtl p34 toolbarHas, "dpad-bar"); the Bind box now does the same: "dpad bar", nothing placed, the box keeps
waiting (a trigger + the D-pad, or the toolbar off). The trigger sets are proven to place where the game's formula says, with the
controller read through either of addon code's two readers; with NO reader the ack says so (probe.src game / shift) - the device
answer the next report carries (the app logs every bind ack now, CompanionHost "spells bind:").
  a toolbar off: bare D-pad Up -> the plain slot (the commenter's "it allowed me to bind abilities to the Dpad");
  b toolbar on: bare D-pad Up -> "dpad bar", no PlaceAction, the box still waits; the In combat tab's bare D-pad still takes a fight
    spell (p36: the toolbar claims only WoW Defaults' D-pad);
  c toolbar on: ZL + D-pad Up, ZR + A, ZL + ZR + X, ZL + Y -> the formula slots of sets 2 / 3 / 4;
  d the controller read through C_GamePad.GetDeviceMappedState (no GetGamePadState): ZR + Y -> set 3, probe.padSrc mapped;
  e no controller reader at all: the ack carries probe.src (game) and pad = nil, so the report can tell;
  f in combat: refused before any placement.
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


# a Mage (no forms, no stance bar), the game's bar slots and the cursor road
EXTRA = r'''
CLASS = "MAGE"
SPELLNAME[133] = "Fireball"; SPELLNAME[116] = "Frostbolt"
BOOK = { 133, 116 }
CVARS = { GamepadStanceBarOverride = "3" }
C_CVar = { GetCVar = function(k) return CVARS[k] end }
C_ActionBar = { HasBonusActionBar = function() return false end }
function GetNumShapeshiftForms() return 0 end
function GetShapeshiftForm() return 0 end
function GetShapeshiftFormInfo() end
C_GamepadUI = {
    GetFirstGamepadActionBarStorageSlotIndexForActiveStance = function() return 0 end,
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
function GetGamePadState() return { buttons = PADSTATE } end
function hold(which) PADSTATE = {}; if which == "LT" or which == "both" then PADSTATE[LT_I] = true end
    if which == "RT" or which == "both" then PADSTATE[RT_I] = true end end
function toolbar(on) if type(WowCompanionSpellsDB) ~= "table" then WowCompanionSpellsDB = {} end
    WowCompanionSpellsDB.dpad = WowCompanionSpellsDB.dpad or {}; WowCompanionSpellsDB.dpad[WHO .. "-" .. REALM] = on end
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


def bind(L, trig, key, fight=False):
    """open the Bind box on Fireball (book slot 1), hold the trigger(s), press [key] through the room's own press; the last ack,
    the places made and the box's mode after"""
    n0 = int(L.eval('#PLACED'))
    a0 = len([d for d in docs(L, 'spellsack') if d.get('op') == 'bind'])
    L.execute('BK.startBind({ t = 1, slot = 1, id = 133 })')
    if fight:
        L.execute('BK.ctl.bind.fight = true')
    L.execute('hold(%s)' % ('nil' if trig is None else '"%s"' % trig))
    L.execute('BK.press("%s")' % key)
    acks = [d for d in docs(L, 'spellsack') if d.get('op') == 'bind'][a0:]
    placed = [L.eval('PLACED[%d]' % i) for i in range(n0 + 1, int(L.eval('#PLACED')) + 1)]
    mode = L.eval('BK.ctl.mode')
    L.execute('hold(nil); BK.ctl.mode = "book"; BK.ctl.bind = nil; BK.ctl.undo = nil')
    return (acks[-1] if acks else {}), placed, mode


def errs(L):
    return list(L.eval('ERRS').values()) if L.eval('ERRS') is not None else []


comp = lupa.LuaRuntime().eval('function(s, n) local f, e = loadstring(s, n); return e end')
e = comp(SRC["book"], "book")
check(e is None, "Lua 5.1 compiles Book.lua", e or '')
check('local BV = "b59"' in SRC["book"], "the version: Book b57 (b50 issues #18 / #20 / #31, b51 rank up, b52 Smart Range, b53 cast bar, b57 eight cast bar slots, b57b L3 / R3, b58 merge)")
check("Any modifier key binding doesn't work" in SRC["book"] and "doesnt show up on bottom screen" in SRC["book"],
      "Book.lua's header note quotes the reports")

# ================================================================ a. the toolbar off: the bare D-pad takes the spell
A = boot('')
A.execute('toolbar(false)')
check(errs(A) == [], "the world loads (a Mage, the DS WoW toolbar off)", errs(A)[:2])
up1 = A.eval('BK.storageFor(1, 1, 2)')
ack, placed, mode = bind(A, None, "PADDUP")
check(ack.get('ok') is True and placed == [up1] and mode == "confirm",
      "a. toolbar off: bare D-pad Up places Fireball on the plain slot (%s), the box asks to keep it" % up1, (ack.get('why'), placed, mode))

# ================================================================ b. the toolbar on: the bare D-pad is the toolbar's
B = boot('')
B.execute('toolbar(true)')
check(B.eval('WowCompanion.padMap.dpadState().want') is True, "b. the DS WoW toolbar is wanted for this character")
ack, placed, mode = bind(B, None, "PADDUP")
check(ack.get('ok') is False and ack.get('why') == "dpad bar" and placed == [],
      "b. toolbar on: bare D-pad Up is refused 'dpad bar', nothing placed (the toolbar's Up would cover it)", (ack.get('why'), placed))
check(mode == "bind", "b. the box keeps waiting for another button (a trigger + the D-pad)", mode)
ctl = docs(B, 'spellsctl')[-1]['ctl']
check(ctl.get('note') == "bind-dpad bar", "b. the room reports bind-dpad bar (the app's line: hold a trigger)", ctl.get('note'))
for k in ("PADDLEFT", "PADDRIGHT", "PADDDOWN"):
    ack, placed, mode = bind(B, None, k)
    check(ack.get('why') == "dpad bar" and placed == [], "b. toolbar on: bare %s refused too" % k, (ack.get('why'), placed))
ack, placed, mode = bind(B, None, "PADDUP", fight=True)
check(ack.get('ok') is True and ack.get('pad', {}).get('fight') is True and placed == [],
      "b. the In combat tab's bare D-pad still takes a fight spell with the toolbar on (p36)", (ack.get('ok'), ack.get('why'), ack.get('pad')))

# ================================================================ c. the trigger sets with the toolbar on
for trig, key, s, p in (("LT", "PADDUP", 2, 2), ("RT", "PAD1", 3, 8), ("both", "PAD3", 4, 5), ("LT", "PAD4", 2, 6), ("RT", "PADDLEFT", 3, 1)):
    want = B.eval('BK.storageFor(1, %d, %d)' % (s, p))
    ack, placed, mode = bind(B, trig, key)
    check(ack.get('ok') is True and placed == [want] and ack.get('pad', {}).get('b') == s and ack.get('pad', {}).get('p') == p
          and ack.get('probe', {}).get('src') == "pad",
          "c. toolbar on: %s + %s -> set %d pos %d, slot %s (probe.src pad)" % (trig, key, s, p, want),
          (ack.get('why'), placed, ack.get('pad')))
check(errs(B) == [], "b / c: no Lua error", errs(B)[:2])

# ================================================================ d. the controller through GetDeviceMappedState
D = boot('')
D.execute('''toolbar(true); GetGamePadState = nil
C_GamePad.GetActiveDeviceID = function() return 3 end
C_GamePad.GetDeviceMappedState = function(dev) if dev == 3 then return { buttons = PADSTATE, axes = {} } end end''')
want = D.eval('BK.storageFor(1, 3, 6)')
ack, placed, mode = bind(D, "RT", "PAD4")
check(ack.get('ok') is True and placed == [want] and ack.get('probe', {}).get('padSrc') == "mapped",
      "d. no GetGamePadState: ZR + Y read through GetDeviceMappedState -> set 3 (slot %s, padSrc mapped)" % want,
      (ack.get('why'), placed, ack.get('probe', {}).get('padSrc')))

# ================================================================ e. no controller reader: the ack says so
E = boot('')
E.execute('toolbar(false); GetGamePadState = nil')
ack, placed, mode = bind(E, "RT", "PADDUP")
pr = ack.get('probe', {})
check(pr.get('pad') is None and pr.get('src') in ("game", "shift") and pr.get('padSrc') == "none",
      "e. no reader: the ack carries pad = nil, src game / shift and padSrc none (the report can tell)", pr)

# ================================================================ f. in combat
Fc = boot('')
Fc.execute('toolbar(true)')
Fc.execute('BK.startBind({ t = 1, slot = 1, id = 133 }); COMBAT = true; hold("LT")')
Fc.execute('BK.press("PADDUP")')
check(int(Fc.eval('#PLACED')) == 0, "f. in combat: nothing placed")
Fc.execute('COMBAT = false')
check(errs(E) == [] and errs(Fc) == [] and errs(D) == [], "d / e / f: no Lua error", (errs(D) + errs(E) + errs(Fc))[:2])

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
