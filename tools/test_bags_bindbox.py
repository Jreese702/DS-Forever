"""BIND TO A BUTTON FROM BAGS = THE SPELLBOOK'S BIND BOX (Book b59, Bags bags-bindbox1), off-device: the REAL add-on files under the
stubbed game (tools/wowstub.py, the sticks world of tools/test_stick_binds.py).

    python tools/test_bags_bindbox.py

The user, 2026-11-13: "when doing bind to a button from bags it should open the normal spell binding menu". Proven here:
  1 WC.spellsBindItem opens the box for a bag item, HOSTED (Bags keeps the controller): the box's document (on, via "bags", mode
    "bind", bitem = the item's id / name / icon, no Cast bar tab), Select / L3 / R3 asked through the host; a second open is refused;
  2 Normal: ZL + D-pad Up puts the item on that slot the game's own way (PickupItem + PlaceAction; the answer: item, the slot), Keep /
    Undo as for a spell; A keeps it and the box hands back to the host (done "bind-kept"), the extra keys given back;
  3 the item's own button pressed again CLEARS it (issue #213's rule, clr), B undoes the clear (the item back), a plain B then backs
    out to the host (done "bind-cancel");
  4 over something else (a spell): B's undo puts that back;
  5 L3: the stick entry ("/use item:<id>", PadMap m157); L3 again: cleared; In combat + L3 refused "stick-normal";
  6 In combat (ZL + X): the fight layer's item (PadMap putFight); again: cleared; B undoes the clear; a base face button (bare Y) too;
  7 the rules: in combat refused (nothing placed), the Cast bar tab never shows for an item, the host's abort closes it quietly;
  8 THE BAGS ROOM ITSELF (Bags.lua loaded in the same world): Y > Bind to a Button opens the box on the cursor's item (Bags mode
    "bindbox"), Bags hands it every press (ZL + D-pad Up binds, A keeps), the room is back on the grid on the SAME slot; a plain B
    in the box backs out to the grid where you were; by touch (bmvq 32 Y, 20 the Bind row, 33 B) the same; the controller lost
    with the box up closes the box;
plus no spied call (no SetBinding, SaveBindings, macro, CVar), no lockdown breach. Needs lupa. Exit code 1 on any failure."""
import json
import os
import re
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
    "bags": os.path.join(ADDONS, "WowCompanion", "Bags.lua"),
}
SRC = {k: open(p, encoding="utf-8").read() for k, p in F.items()}
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import GAME  # noqa: E402
PARTY = open(os.path.join(ROOT, "tools", "test_party.py"), encoding="utf-8").read()
WORLD_HELPERS = re.search(r"\nWORLD_HELPERS = r'''(.*?)'''", PARTY, re.S).group(1)
STICKS = open(os.path.join(ROOT, "tools", "test_stick_binds.py"), encoding="utf-8").read()
EXTRA = re.search(r"\nEXTRA = r'''(.*?)'''", STICKS, re.S).group(1)
AFTER = re.search(r"\nAFTER = r'''(.*?)'''", STICKS, re.S).group(1)
MORE = r'''
function GetGamePadState() return { buttons = PADSTATE } end
function hold(which) PADSTATE = {}; if which == "LT" or which == "both" then PADSTATE[LT_I] = true end
    if which == "RT" or which == "both" then PADSTATE[RT_I] = true end end
-- the game's bar slots and cursor road (tools/test_book_bind.py's)
C_GamepadUI = {
    GetFirstGamepadActionBarStorageSlotIndexForActiveStance = function() return 0 end,
    GetFirstGamepadActionStorageSlotIndex = function() return 181 end,
    IsValidGamepadActionStorageSlotIndex = function() return true end,
}
Constants = { GamepadActionBarConstants = { NUM_SLOTS_PER_GAMEPAD_ACTION_BAR = 8,
    NUM_PAGEABLE_SLOTS_PER_GAMEPAD_ACTION_BAR_PAGE_UNIT_STANDARD_PAGE = 28, NUM_RESERVED_SLOTS_PER_GAMEPAD_ACTION_BAR_PAGE_UNIT = 4 } }
CURSOR = nil
PLACED = {}
function GetCursorInfo() if CURSOR then return CURSOR[1], CURSOR[2] end end
function ClearCursor() CURSOR = nil end
function PlaceAction(s) PLACED[#PLACED + 1] = s; local old = ACTIONINFO[s]; ACTIONINFO[s] = CURSOR; CURSOR = old end
function PickupAction(s) CURSOR = ACTIONINFO[s]; ACTIONINFO[s] = nil end
function PickupItem(id) CURSOR = { "item", id } end
C_Spell.PickupSpell = function(id) CURSOR = { "spell", id } end
C_Item.GetItemNameByID = function(id) return ({ [6948] = "Hearthstone", [118] = "Minor Healing Potion" })[id] end
-- the stub's capture gains the guest's calls (take / release / extra / pass): the extra keys asked for, by whom
EXTRA_LIST, EXTRA_BY = nil, nil
WowCompanion.capture.take = function(guest, spec) CAPSPEC = spec; HOLDER = guest; return true end
WowCompanion.capture.release = function(guest) if HOLDER == guest then HOLDER = nil end; return true end
WowCompanion.capture.extra = function(guest, list) EXTRA_LIST = table.concat(list, ","); EXTRA_BY = guest; return true end
WowCompanion.capture.pass = function() return true end
WowCompanion.capture.refused = function(guest) REFUSED_BY = guest; return false end
-- a host as Bags is one: its extra keys and its done notes
HOSTX, DONE = nil, {}
HOST = { name = "bags", extra = function(list) HOSTX = table.concat(list, ","); return true end,
         done = function(note) DONE[#DONE + 1] = note end }
'''
BAGS_MORE = r'''
BAG = { [0] = { [1] = 6948, [2] = 118 } }
C_Container = { GetContainerItemID = function(b, s) return BAG[b] and BAG[b][s] end,
                GetContainerNumSlots = function(b) return b == 0 and 4 or 0 end,
                GetContainerNumFreeSlots = function(b) return b == 0 and 2 or 0, 0 end,
                GetContainerItemInfo = function(b, s) if BAG[b] and BAG[b][s] then return { iconFileID = 1, quality = 1, stackCount = 1 } end end }
for _, n in ipairs({ "MerchantFrame", "GossipFrame", "QuestFrame", "BankFrame", "MailFrame", "TradeFrame", "AuctionHouseFrame",
                     "GuildBankFrame", "ClassTrainerFrame", "CharacterFrame" }) do _G[n] = { IsShown = function() return false end } end
'''
fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


def boot(bags=False):
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(GAME)
    L.execute(EXTRA)
    L.execute(MORE)
    if bags:
        L.execute(BAGS_MORE)
    load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    for k in ("safety", "tappad", "book", "padmap", "padtabctl") + (("bags",) if bags else ()):
        load(SRC[k], '@' + os.path.basename(F[k]))(os.path.basename(F[k]))
    if bags:
        L.execute('WowCompanion._bagsLayout = { { bag = 0, slots = 4 } }')
    L.execute(WORLD_HELPERS)
    L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
    L.execute(AFTER)
    L.execute('local c = WowCompanion._spellsBook.ctl; c.croom, c.ctrlTab, c.onTabs = false, false, false')
    L.execute('BK = WowCompanion._spellsBook; HB = WowCompanion.spellsBindHosted; PM = WowCompanion.padMap')
    L.execute('WowCompanion.padMap.emit(true); runTimers(NOW + 1)')
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


def sctl(L):
    d = docs(L, 'spellsctl')
    return d[-1]['ctl'] if d else {}


def acks(L, a0=0):
    return [d for d in docs(L, 'spellsack') if d.get('op') == 'bind'][a0:]


def nacks(L):
    return len(acks(L))


def entries(L):
    t = L.eval('(WowCompanionSpellsDB.stickBy or {})[WHO .. "-" .. REALM]')
    return {k: dict(v) for k, v in dict(t).items()} if t is not None else {}


def line(L, name):
    return L.eval('(function() local b = rawget(_G, "%s"); return b and b.a and b.a.macrotext end)()' % name)


def hpress(L, key, trig=None):
    """a press the host hands on, with the trigger(s) held; the last answer it brought"""
    a0 = nacks(L)
    L.execute('hold(%s)' % ('nil' if trig is None else '"%s"' % trig))
    L.execute('HB.press("%s")' % key)
    L.execute('hold(nil)')
    a = acks(L, a0)
    return a[-1] if a else {}


def fight_ref(L, page, s, pos):
    v = L.eval('(function() local v = PM.slotAt(%d, %d, %d); return v and v.kind == "combat" and v.c and v.c.t .. "|" .. tostring(v.c.key) end)()'
               % (page, s, pos))
    return v


def clean(L, name):
    check(L.eval('#SPY') == 0 and L.eval('#VIOL') == 0 and list(L.eval('ERRS').values()) == [],
          name + ": no spied call (no trace), no lockdown breach, no Lua error",
          (L.eval('SPY[1]'), L.eval('VIOL[1]'), list(L.eval('ERRS').values())[:2]))


comp = lupa.LuaRuntime().eval('function(s, n) local f, e = loadstring(s, n); return e end')
for k in ("book", "bags"):
    e = comp(SRC[k], k)
    check(e is None, "Lua 5.1 compiles " + os.path.basename(F[k]), e or '')
check('local BV = "b62"' in SRC["book"] and "when doing bind to a button from bags it should open the normal spell binding menu" in SRC["book"],
      "Book b59, its header note quotes the user")
check("bags-bindbox1" in SRC["bags"] and "when doing bind to a button from bags it should open the normal spell binding menu" in SRC["bags"],
      "Bags bags-bindbox1, its header note quotes the user")
check("onChord" not in SRC["bags"].split("bags-bindbox1", 2)[2] and "armBind" not in SRC["bags"].split("bags-bindbox1", 2)[2]
      and "cap.chord" not in SRC["bags"], "the old bind sheet's chord flow is gone from Bags")

# ================================================================ 1. the hosted box opens
A = boot()
ok = A.eval('WowCompanion.spellsBindItem(6948, HOST)')
c = sctl(A)
check(ok is True and c.get('on') is True and c.get('via') == "bags" and c.get('mode') == "bind" and c.get('bind') == 6948,
      "1. the box opens for Hearthstone, hosted (on, via bags, mode bind)", c)
check(c.get('bitem') == {"id": 6948, "n": "Hearthstone", "i": 134414}, "1. bitem: the item's id, name and icon", c.get('bitem'))
check(c.get('bcb') is None, "1. no Cast bar tab for an item", c.get('bcb'))
hx = A.eval('HOSTX') or ''
check("PADBACK" in hx and "PADLSTICK" in hx and "PADRSTICK" in hx and A.eval('rawget(_G, "EXTRA_BY")') is None,
      "1. Select / L3 / R3 asked through the HOST (Bags' name at the capture), never as the Spells guest", (hx, A.eval('rawget(_G, "EXTRA_BY")')))
check(A.eval('select(2, WowCompanion.spellsBindItem(118, HOST))') == "busy", "1. a second box while one is up: refused (busy)")
check(A.eval('BK.ctl.on') is False, "1. the Spells room does not hold the controller", A.eval('BK.ctl.on'))

# ================================================================ 2. Normal: ZL + D-pad Up
s22 = A.eval('BK.storageFor(1, 2, 2)')
a = hpress(A, "PADDUP", "LT")
check(a.get('ok') is True and a.get('item') is True and a.get('id') == 6948 and a.get('pad', {}).get('s') == s22
      and a.get('pad', {}).get('b') == 2 and a.get('pad', {}).get('p') == 2 and not a.get('clr'),
      "2. ZL + D-pad Up: the item on slot %s (the answer: item, set 2 position 2)" % s22, a)
check(A.eval('ACTIONINFO[%d][1]' % s22) == "item" and A.eval('ACTIONINFO[%d][2]' % s22) == 6948 and A.eval('rawget(_G, "CURSOR")') is None,
      "2. the game's own way: the slot holds the item, the cursor is empty", (A.eval('ACTIONINFO[%d] and ACTIONINFO[%d][1]' % (s22, s22))))
check(A.eval('BK.ctl.mode') == "confirm" and sctl(A).get('bitem', {}).get('id') == 6948, "2. the box asks Keep / Undo (bitem still sent)",
      A.eval('BK.ctl.mode'))
A.execute('HB.press("PAD1")')
c = sctl(A)
check(list(A.eval('DONE').values()) == ["bind-kept"] and c.get('on') is False and c.get('mode') == "book" and c.get('bitem') is None,
      "2. A keeps it: the box ends and hands back to the host (done bind-kept), the document off", (list(A.eval('DONE').values()), c))
check(A.eval('HOSTX') == "" and A.eval('HB.up()') is False, "2. the extra keys given back through the host; no box up", A.eval('HOSTX'))
check(A.eval('ACTIONINFO[%d][2]' % s22) == 6948, "2. kept: the item stays on the slot")
check(A.eval('HB.press("PAD1")') is False, "2. a press with no box: not the box's (the host's own)")

# ================================================================ 3. the same button again: cleared; B undoes; a plain B backs out
A.execute('DONE = {}; WowCompanion.spellsBindItem(6948, HOST)')
a = hpress(A, "PADDUP", "LT")
check(a.get('ok') is True and a.get('clr') is True and A.eval('ACTIONINFO[%d]' % s22) is None,
      "3. the item's own button again: CLEARED (clr), the slot empty", (a, A.eval('ACTIONINFO[%d]' % s22)))
check(A.eval('BK.ctl.mode') == "confirm", "3. the clear asks Keep / Undo too", A.eval('BK.ctl.mode'))
A.execute('HB.press("PAD2")')                                           # a plain B: undo the clear
check(A.eval('ACTIONINFO[%d] and ACTIONINFO[%d][2]' % (s22, s22)) == 6948 and A.eval('BK.ctl.mode') == "bind",
      "3. B undoes the clear: the item is back, the box waits again", A.eval('BK.ctl.mode'))
A.execute('HB.press("PAD2")')                                           # a plain B: back out
check(list(A.eval('DONE').values()) == ["bind-cancel"] and sctl(A).get('on') is False,
      "3. a plain B backs out of the box to the host (done bind-cancel)", list(A.eval('DONE').values()))

# ================================================================ 4. over a spell: B puts the spell back
s24 = A.eval('BK.storageFor(1, 2, 4)')
A.execute('ACTIONINFO[%d] = { "spell", 403 }; DONE = {}; WowCompanion.spellsBindItem(6948, HOST)' % s24)
a = hpress(A, "PADDDOWN", "LT")
check(a.get('ok') is True and A.eval('ACTIONINFO[%d][1]' % s24) == "item", "4. ZL + D-pad Down over Lightning Bolt: the item there", a)
A.execute('HB.press("PAD2")')
check(A.eval('ACTIONINFO[%d][1]' % s24) == "spell" and A.eval('ACTIONINFO[%d][2]' % s24) == 403,
      "4. B: Lightning Bolt is back", A.eval('ACTIONINFO[%d] and ACTIONINFO[%d][1]' % (s24, s24)))
A.execute('HB.press("PAD2")')
clean(A, "1-4")

# ================================================================ 5. L3: the stick entry
A.execute('DONE = {}; WowCompanion.spellsBindItem(6948, HOST)')
a = hpress(A, "PADLSTICK")
e9 = entries(A).get("1:9", {})
check(a.get('ok') is True and a.get('pad', {}).get('p') == 9 and 's' not in a.get('pad', {}) and e9.get('t') == "item" and e9.get('id') == 6948,
      "5. L3 takes the item: the stick's entry (no bar slot)", (a, e9))
check(line(A, "WowCompanionStickL3") == "/use item:6948", "5. the stick's own button runs /use item:6948", line(A, "WowCompanionStickL3"))
A.execute('HB.press("PAD1")')
A.execute('WowCompanion.spellsBindItem(6948, HOST)')
a = hpress(A, "PADLSTICK")
check(a.get('ok') is True and a.get('clr') is True and "1:9" not in entries(A), "5. L3 again: cleared (the stick's entry gone)", (a, entries(A)))
A.execute('HB.press("PAD2")')                                           # B: the clear undone
check(entries(A).get("1:9", {}).get('id') == 6948, "5. B undoes the clear: the stick holds the item again", entries(A).get("1:9"))
A.execute('hold("LT"); HB.press("PADLSTICK"); hold(nil); HB.press("PAD1")')   # ZL + L3 = a different place: set 2
check(entries(A).get("2:9", {}).get('id') == 6948 and entries(A).get("1:9", {}).get('id') == 6948,
      "5. ZL + L3 (set 2) takes it too, kept; L3's stays", sorted(entries(A).keys()))
A.execute('WowCompanion.spellsBindItem(6948, HOST); WowCompanion._spellsBookKey("bf-on")')
a = hpress(A, "PADRSTICK")
check(a.get('ok') is False and a.get('why') == "stick-normal" and A.eval('BK.ctl.mode') == "bind",
      "5. In combat + R3: stick-normal, the box keeps waiting", a)
A.execute('HB.press("PAD2")')
clean(A, "5")

# ================================================================ 6. In combat: the fight layer
A.execute('DONE = {}; WowCompanion.spellsBindItem(6948, HOST); WowCompanion._spellsBookKey("bf-on")')
check(sctl(A).get('bf') is True, "6. the In combat tab by touch (bf-on)", sctl(A).get('bf'))
a = hpress(A, "PAD3", "LT")
check(a.get('ok') is True and a.get('pad', {}).get('fight') is True and fight_ref(A, 1, 2, 5) == "item|i:6948",
      "6. ZL + X on In combat: the button's fight item (PadMap putFight)", (a, fight_ref(A, 1, 2, 5)))
A.execute('HB.press("PAD2")')                                           # B: undone, nothing was there
check(not fight_ref(A, 1, 2, 5) and A.eval('BK.ctl.mode') == "bind", "6. B undoes it: no fight thing there", fight_ref(A, 1, 2, 5))
a = hpress(A, "PAD3", "LT")
A.execute('HB.press("PAD1")')
check(fight_ref(A, 1, 2, 5) == "item|i:6948" and list(A.eval('DONE').values())[-1:] == ["bind-kept"], "6. again and A: kept")
A.execute('WowCompanion.spellsBindItem(6948, HOST); WowCompanion._spellsBookKey("bf-on")')
a = hpress(A, "PAD3", "LT")
check(a.get('ok') is True and a.get('clr') is True and not fight_ref(A, 1, 2, 5), "6. the item's own fight button again: cleared",
      (a, fight_ref(A, 1, 2, 5)))
A.execute('HB.press("PAD2")')
check(fight_ref(A, 1, 2, 5) == "item|i:6948", "6. B undoes the clear: the fight item back", fight_ref(A, 1, 2, 5))
A.execute('HB.press("PAD2")')                                           # the box waits again: a plain B backs out
A.execute('WowCompanion.spellsBindItem(6948, HOST); WowCompanion._spellsBookKey("bf-on")')
a = hpress(A, "PAD4")                                                   # bare Y: the base face button's fight action
face = A.eval('(function() local v = PM.slotAt(1, 1, 6); return v and v.cf and v.cf.t end)()')
check(a.get('ok') is True and face == "item", "6. bare Y on In combat: the base Y's fight item", (a, face))
A.execute('HB.press("PAD1")')
A.execute('WowCompanion.spellsBindItem(6948, HOST); WowCompanion._spellsBookKey("bf-on")')
a = hpress(A, "PAD2", "LT")                                             # ZL + B is a place; plain B is not
check(a.get('ok') is True, "6. ZL + B on In combat: a place for the item", a)
A.execute('HB.press("PAD1")')
clean(A, "6")

# ================================================================ 7. the rules
A.execute('DONE = {}; COMBAT = true')
check(A.eval('select(2, WowCompanion.spellsBindItem(6948, HOST))') == "combat" and A.eval('HB.up()') is False,
      "7. in a fight the box does not open (combat)")
A.execute('COMBAT = false; WowCompanion.spellsBindItem(6948, HOST); COMBAT = true')
n0 = int(A.eval('#PLACED'))
a = hpress(A, "PADDRIGHT", "LT")
check(a.get('ok') is False and a.get('why') == "combat" and int(A.eval('#PLACED')) == n0, "7. a fight with the box up: refused, nothing placed", a)
check(A.eval('REFUSED_BY') == "bags", "7. ... the red flash asked for the HOLDER (Bags), not the Spells guest", A.eval('REFUSED_BY'))
A.execute('COMBAT = false; runTimers(NOW + 5)')
check(A.eval('HB.up()') is False and list(A.eval('DONE').values()) == ["result-closed"],
      "7. the refusal's answer closes after 1.5 s and the box hands back (result-closed)", list(A.eval('DONE').values()))
A.execute('DONE = {}; WowCompanion.spellsBindItem(6948, HOST); BK.ctl.bind.cb = true')
A.execute('HB.press("PADDRIGHT")')                                     # (forced) the Cast bar tab's cursor moves: a report
check(sctl(A).get('note') == "bind-cb" and sctl(A).get('bcb') is None, "7. an item's box never carries a Cast bar slot", sctl(A).get('bcb'))
A.execute('BK.ctl.bind.cb = nil')
A.execute('HB.abort("taken")')
c = sctl(A)
check(c.get('on') is False and c.get('note') == "bind-lost-taken" and list(A.eval('DONE').values()) == [] and A.eval('HB.up()') is False,
      "7. the host's abort (its controller lost): the box closes quietly, no done", (c.get('note'), list(A.eval('DONE').values())))
check(A.eval('HOSTX') == "", "7. ... and Select / L3 / R3 are given back through the host", A.eval('HOSTX'))
check(A.eval('(WowCompanion.spellsBindItem(99999, HOST))') is False and A.eval('HB.up()') is False, "7. an unknown item: no box")
clean(A, "7")

# ================================================================ 8. THE BAGS ROOM (Bags.lua in the same world)
G = boot(bags=True)
G.execute('WowCompanion._bagsTouch(29)')
check(G.eval('HOLDER') == "bags" and G.eval('WowCompanion._bagsCtlDoc().on') is True, "8. the Bags room took the controller",
      G.eval('HOLDER'))


def bdoc(L):
    return L.eval('WowCompanion._bagsCtlDoc()')


def bpress(L, key, trig=None):
    L.execute('hold(%s)' % ('nil' if trig is None else '"%s"' % trig))
    L.execute('CAPSPEC.press("%s")' % key)
    L.execute('hold(nil)')


menu = list(G.eval('WowCompanion._bagsCtlDoc().menus or {}').values()) if G.eval('WowCompanion._bagsCtlDoc().menus') else []
bpress(G, "PAD4")                                                       # Y: More
d = bdoc(G)
check(d.mode == "menu" and d.menu == "bind", "8. Y on the Hearthstone: the menu opens on Bind to a Button (a single item)",
      (d.mode, d.menu))
bpress(G, "PAD1")                                                       # A: Bind to a Button
d = bdoc(G)
c = sctl(G)
check(d.mode == "bindbox" and d.boxItem == 6948 and c.get('on') is True and c.get('via') == "bags" and c.get('bitem', {}).get('id') == 6948,
      "8. A on Bind: the Spellbook's Bind box for the item (Bags mode bindbox, the box's document)", (d.mode, c.get('via')))
check(G.eval('EXTRA_BY') == "bags" and "PADLSTICK" in (G.eval('EXTRA_LIST') or ""),
      "8. the box's extra keys asked at the capture under Bags' name", (G.eval('EXTRA_BY'), G.eval('EXTRA_LIST')))
s23 = G.eval('BK.storageFor(1, 2, 3)')
bpress(G, "PADDRIGHT", "LT")                                            # ZL + D-pad Right
check(G.eval('ACTIONINFO[%d] and ACTIONINFO[%d][2]' % (s23, s23)) == 6948 and G.eval('BK.ctl.mode') == "confirm"
      and bdoc(G).mode == "bindbox", "8. Bags hands the press to the box: the item on ZL + D-pad Right, Keep / Undo", G.eval('BK.ctl.mode'))
bpress(G, "PAD1")                                                       # A: keep
d = bdoc(G)
check(d.mode == "grid" and d.cur.bag == 0 and d.cur.slot == 1 and d.note == "bind-kept" and sctl(G).get('on') is False,
      "8. A keeps it: the room is back on the grid on the same slot", (d.mode, d.cur.slot, d.note))
check(G.eval('EXTRA_LIST') == "", "8. the extra keys given back", G.eval('EXTRA_LIST'))
bpress(G, "PADDRIGHT")                                                  # the cursor to slot 2 (the potion)
bpress(G, "PAD4"); bpress(G, "PAD1")
check(bdoc(G).mode == "bindbox" and sctl(G).get('bitem', {}).get('id') == 118, "8. slot 2: the box for the potion", sctl(G).get('bitem'))
bpress(G, "PAD2")                                                       # a plain B: back out
d = bdoc(G)
check(d.mode == "grid" and d.cur.slot == 2 and d.note == "bind-cancel" and sctl(G).get('on') is False and G.eval('HOLDER') == "bags",
      "8. a plain B in the box: back to the Bags grid where you were (slot 2), the room keeps the controller", (d.mode, d.cur.slot, d.note))
# by touch: Y (bmvq 32), the menu's Bind row (bmvq 20), then B (bmvq 33)
G.execute('WowCompanion._bagsTouch(32)')
G.execute('WowCompanion._bagsTouch(20)')
check(bdoc(G).mode == "bindbox" and sctl(G).get('bitem', {}).get('id') == 118, "8. by touch: More, then the Bind row: the box opens",
      bdoc(G).mode)
G.execute('WowCompanion._bagsTouch(33)')
check(bdoc(G).mode == "grid" and bdoc(G).cur.slot == 2 and sctl(G).get('on') is False, "8. a finger's B (the box's Back): the grid again",
      bdoc(G).mode)
# the clear by repress through the room, touch Keep (A = bmvq 31)
bpress(G, "PADDLEFT"); bpress(G, "PAD4"); bpress(G, "PAD1"); bpress(G, "PADDRIGHT", "LT")
check(G.eval('ACTIONINFO[%d]' % s23) is None and G.eval('BK.ctl.mode') == "confirm", "8. the Hearthstone's own button again: cleared")
G.execute('WowCompanion._bagsTouch(31)')
check(bdoc(G).mode == "grid" and G.eval('ACTIONINFO[%d]' % s23) is None, "8. a finger's Keep: cleared for good, the grid", bdoc(G).mode)
# the controller lost with the box up
bpress(G, "PAD4"); bpress(G, "PAD1")
check(G.eval('HB.up()') is True, "8. the box up again")
G.execute('CAPSPEC.lost("taken")')
check(G.eval('HB.up()') is False and sctl(G).get('on') is False and bdoc(G).on is False,
      "8. the controller lost (another room): the box closes with it", sctl(G).get('note'))
# the room left another way while the box is up (the touch leave): the box goes too
G.execute('WowCompanion._bagsTouch(29)')
bpress(G, "PAD4"); bpress(G, "PAD1")
G.execute('WowCompanion._bagsTouch(30)')
check(G.eval('HB.up()') is False and sctl(G).get('on') is False, "8. the room left by touch with the box up: the box closes",
      sctl(G).get('note'))
clean(G, "8")

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
