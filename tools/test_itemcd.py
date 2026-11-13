"""THE ITEM COOLDOWNS (Radial.lua r20 `itemcd`, feat/item-cooldowns; the user's picks 2026-11-13), off-device: the REAL Safety / TapPad /
Radial files under tools/wowstub.py's stubbed game, Lua 5.1 as the game's.

    python tools/test_itemcd.py

The user's words: "itemcd document per 1a: event-driven only (BAG_UPDATE_COOLDOWN, PLAYER_REGEN_ENABLED/DISABLED, PLAYER_ENTERING_WORLD),
0.3 s merge, change-only, NEVER calls collect()/pad.retain/buildCells; remove BAG_UPDATE_COOLDOWN from the item radial's rebuild list;
honour enable=0 (held potion: dim, no number)"; "LEARNED LENGTHS in the style of the existing facecd ... if never learned, show the
never-secret isActive dim only. Also: a countdown that started before the fight keeps running"; "Counts only on stackable
consumables/reagents/ammo/items with charges (rule 1d)"; the class teleport "only once known". Exit code 1 on a failure."""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lupa import lua51 as lupa                    # noqa: E402
from wowstub import ADDONS, GAME, docs, nsent     # noqa: E402

RADIAL = os.path.join(ADDONS, "WowCompanionSpells", "Radial.lua")
FILES = [os.path.join(ADDONS, "WowCompanion", "Safety.lua"), os.path.join(ADDONS, "WowCompanion", "TapPad.lua"), RADIAL]
SRC = open(RADIAL, encoding="utf-8").read()
fails = passes = 0


def check(ok, name, got=''):
    global fails, passes
    if ok:
        passes += 1; print("PASS", name)
    else:
        fails += 1; print("FAIL", name, got)


# the bags: the Hearthstone (class 15, one, a Use), bread (class 0 / 5, stacks of 20), a potion (0 / 1), a quest tool not cached yet
BAGS = r'''
ITEMS = {
  [6948] = { cls = 15, sub = 4, stack = 1, use = 8690, n = "Hearthstone", i = 134414 },
  [4540] = { cls = 0, sub = 5, stack = 20, use = 433, n = "Tough Hunk of Bread", i = 133964 },
  [118]  = { cls = 0, sub = 1, stack = 5, use = 439, n = "Minor Healing Potion", i = 134829 },
  [5000] = { cls = 12, sub = 0, stack = nil, use = 7000, n = "Quest Tool", i = 134400 },
}
BAG0 = { { id = 6948, c = 1 }, { id = 4540, c = 5 }, { id = 118, c = 2 }, { id = 5000, c = 1 } }
ITEMCD = {}                        -- id -> { st, dur, en } | "secret"
NSLOTS = 0                         -- bag walks (collect's scan / scanUsable) seen
C_Container = {
  GetContainerNumSlots = function(bag) NSLOTS = NSLOTS + 1; if bag == 0 then return #BAG0 end return 0 end,
  GetContainerItemID = function(bag, slot) local e = bag == 0 and BAG0[slot]; return e and e.id or nil end,
  GetContainerItemInfo = function(bag, slot) local e = bag == 0 and BAG0[slot]; if not e then return nil end
      return { itemID = e.id, stackCount = e.c, iconFileID = ITEMS[e.id].i } end,
  GetContainerItemQuestInfo = function() return { isQuestItem = false } end,
  GetItemCooldown = function(id) local c = ITEMCD[id]; if c == "secret" then return SECRETV, SECRETV, SECRETV end
      if c then return c[1], c[2], c[3] end; return 0, 0, 1 end,
}
C_Item = {
  GetItemInfoInstant = function(id) local x = ITEMS[id]; if not x then return nil end; return id, "t", "s", "", x.i, x.cls, x.sub end,
  GetItemSpell = function(id) local x = ITEMS[id]; if x and x.use then return "Use " .. x.n, x.use end end,
  GetItemNameByID = function(id) local x = ITEMS[id]; return x and x.n end,
  GetItemIconByID = function(id) local x = ITEMS[id]; return x and x.i end,
  GetItemInfo = function(id) local x = ITEMS[id]; if not x or not x.stack then return nil end
      return x.n, "link", 1, 1, 1, "t", "s", x.stack end,
  GetItemCount = function(id) local n = 0; for _, e in ipairs(BAG0) do if e.id == id then n = n + e.c end end; return n end,
}
function GetInventoryItemID() return nil end
KNOWN = {}
function IsPlayerSpell(id) return KNOWN[id] == true end
function IsSpellKnown(id) return KNOWN[id] == true end
SPELLNAME[556] = "Astral Recall"
CDS = {}
C_Spell.GetSpellCooldown = function(id) local c = CDS[id]; if c == "secret" then return { startTime = SECRETV, duration = SECRETV, isActive = true } end
    return c or { startTime = 0, duration = 0, isActive = false } end
C_Spell.GetSpellInfo = function(id) return { name = SPELLNAME[id], iconID = 130000 + id, spellID = id } end
'''


def world():
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(GAME)
    L.execute(BAGS)
    load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    for p in FILES:
        load(open(p, encoding="utf-8").read(), '@' + os.path.basename(p))(os.path.basename(p))
    # the spies on the secure / rebuild paths (the pad's own functions, wrapped after the load: Radial reads them off WC.pad each call)
    L.execute('''
PADCALLS = 0
for _, k in ipairs({ "secureRow", "retain", "slotFor" }) do
    local orig = WowCompanion.pad[k]
    WowCompanion.pad[k] = function(...) PADCALLS = PADCALLS + 1; return orig(...) end
end
''')
    L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
    return L


def last(L, start, typ):
    d = docs(L, start, typ)
    return d[-1] if d else None


# ---------------------------------------------------------------- 1. the source: the itemcd block never rebuilds anything
blk = SRC.split("r20: THE ITEM COOLDOWNS (`itemcd`)", 1)[1].split("r3: THE STICK CATCH", 1)[0]
code = "\n".join(l.split("--", 1)[0] for l in blk.splitlines())
for bad in ("collect(", "buildCells(", "pad.", "assign(", "scan(", "scanUsable(", "pinColumns(", "emit("):
    check(bad not in code.replace("cdEmit(", "").replace("pcall(cdEmit", ""), "the itemcd block's code calls no %s" % bad)
check(not re.search(r'"BAG_UPDATE_DELAYED", "BAG_UPDATE_COOLDOWN"', SRC), "BAG_UPDATE_COOLDOWN is off the item radial's rebuild list")

L = world()
check(L.eval('ERRS[1]') is None, "the files load and log in with no error", L.eval('ERRS[1]'))
first = last(L, 0, 'itemcd')
check(first is not None and 't' not in first and first.get('hs', {}).get('have') is True and first['hs'].get('i') == 134414,
      "login: an itemcd with nothing cooling and the Hearthstone in the bags", first)
r = last(L, 0, 'radial')
items = {d['id']: d for d in (r or {}).get('r', {}).get('items', [])}
check(items.get(4540, {}).get('st') is True and items.get(118, {}).get('st') is True, "bread and the potion stack (st)", items)
check(items.get(6948, {}).get('st') is False, "the Hearthstone does not stack (no count)", items.get(6948))
check(items.get(5000) is not None and 'st' not in items[5000], "the uncached quest tool: no st (the count shows, asked again)", items.get(5000))
check(all('cd' not in d for d in items.values()), "the radial document carries no cd any more (itemcd does)", items)

# ---------------------------------------------------------------- 2. BAG_UPDATE_COOLDOWN: one merged, change-only itemcd, no rebuild
n0, s0, p0 = nsent(L), L.eval('NSLOTS'), L.eval('PADCALLS')
L.execute('ITEMCD[6948] = { NOW, 1800, 1 }')
for _ in range(10):
    L.execute('fire("BAG_UPDATE_COOLDOWN")')
L.execute('runTimers(NOW + 1)')
cds = docs(L, n0, 'itemcd')
check(len(cds) == 1, "10 BAG_UPDATE_COOLDOWNs in a moment: ONE itemcd (the 0.3 s merge)", len(cds))
hs = cds and cds[0].get('t', {}).get('6948')
check(hs and hs.get('d') == 1800 and 1798 <= hs.get('l', 0) <= 1800, "the Hearthstone: d 1800, l ~ 1800", hs)
check(not docs(L, n0, 'radial'), "no radial document from a cooldown event", len(docs(L, n0, 'radial')))
check(L.eval('NSLOTS') == s0, "no bag walk (collect's scan) on a cooldown event", L.eval('NSLOTS') - s0)
check(L.eval('PADCALLS') == p0, "no pad call (secureRow / retain / slotFor) on a cooldown event", L.eval('PADCALLS') - p0)
# the control: the spies DO see a real rebuild (a bag change)
s1, p1 = L.eval('NSLOTS'), L.eval('PADCALLS')
L.execute('fire("BAG_UPDATE_DELAYED"); runTimers(NOW + 1)')
check(L.eval('NSLOTS') > s1 and L.eval('PADCALLS') > p1, "(control) a bag change does walk the bags and use the pad", (L.eval('NSLOTS') - s1, L.eval('PADCALLS') - p1))
n0 = nsent(L)
L.execute('advance(5); fire("BAG_UPDATE_COOLDOWN"); runTimers(NOW + 1)')
check(not docs(L, n0, 'itemcd'), "the same cooldown again: nothing (change-only)", docs(L, n0, 'itemcd'))
learned = L.eval('(function() local t = WowCompanionSpellsDB.cdLearn and WowCompanionSpellsDB.cdLearn["Korvash-Realm"]; return t and t["i6948"] end)()')
check(learned == 1800, "the plain read learned its length, per character (name-realm)", learned)

# ---------------------------------------------------------------- 3. held (enable = 0): h, no number
n0 = nsent(L)
L.execute('ITEMCD[118] = { 0, 120, 0 }; fire("BAG_UPDATE_COOLDOWN"); runTimers(NOW + 1)')
d = last(L, n0, 'itemcd')
check(d and d.get('t', {}).get('118') == {"h": True}, "a held potion (enable 0): { h = true }", d and d.get('t'))

# ---------------------------------------------------------------- 4. a fight: secret values
L.execute('ITEMCD[118] = { NOW, 120, 1 }; fire("BAG_UPDATE_COOLDOWN"); runTimers(NOW + 1)')        # the potion learned: 120 s
L.execute('ITEMCD[118] = nil; fire("BAG_UPDATE_COOLDOWN"); runTimers(NOW + 1)')                    # ready again (a plain read)
n0 = nsent(L)
L.execute('COMBAT = true; fire("PLAYER_REGEN_DISABLED"); ITEMCD[6948] = "secret"; ITEMCD[118] = "secret"; ITEMCD[4540] = "secret"; runTimers(NOW + 1)')
d = last(L, n0, 'itemcd')
check(d and d.get('fight') is True and d.get('t', {}).get('6948', {}).get('d') == 1800,
      "the fight starts (forced doc): the Hearthstone's countdown from before the fight keeps running", d)
check(d and '118' not in d.get('t', {}), "the potion (ready before the fight, secret now, not used): nothing, never a guess", d and d.get('t'))
n0 = nsent(L)
L.execute('fire("UNIT_SPELLCAST_SUCCEEDED", "player", "Cast-1", 439); fire("BAG_UPDATE_COOLDOWN"); runTimers(NOW + 1)')
d = last(L, n0, 'itemcd')
p = d and d.get('t', {}).get('118')
check(p and p.get('d') == 120 and p.get('e') is True and 118 <= p.get('l', 0) <= 120,
      "the potion used in the fight (its Use spell's cast), secret read: the LEARNED 120 s, estimated (e)", p)
n0 = nsent(L)
L.execute('fire("UNIT_SPELLCAST_SUCCEEDED", "player", "Cast-2", 433); fire("BAG_UPDATE_COOLDOWN"); runTimers(NOW + 1)')
d = last(L, n0, 'itemcd')
check(d and d.get('t', {}).get('4540') == {"x": True}, "bread used in the fight, its length never learned: { x = true } (dim, no number)", d and d.get('t'))
check(L.eval('#VIOL') == 0 and L.eval('#SPY') == 0, "nothing protected, no game window call, in the fight", (L.eval('VIOL[1]'), L.eval('SPY[1]')))
check(L.eval('ERRS[1]') is None, "no error on the secret paths", L.eval('ERRS[1]'))
n0 = nsent(L)
L.execute('COMBAT = false; ITEMCD[6948] = { NOW - 10, 1800, 1 }; ITEMCD[118] = nil; ITEMCD[4540] = nil; fire("PLAYER_REGEN_ENABLED"); runTimers(NOW + 1)')
d = last(L, n0, 'itemcd')
check(d and 'fight' not in d and set(d.get('t', {})) == {'6948'}, "the fight's end: the plain reads (the dims and the estimate go)", d and d.get('t'))

# ---------------------------------------------------------------- 5. the class teleport, once known
check('tp' not in (d or {}), "no teleport known: no tp", d and d.get('tp'))
n0 = nsent(L)
L.execute('KNOWN[556] = true; CDS[556] = { startTime = NOW, duration = 900, isActive = true }; '
          'fire("UNIT_SPELLCAST_SUCCEEDED", "player", "Cast-3", 556); runTimers(NOW + 1)')
d = last(L, n0, 'itemcd')
tp = d and d.get('tp')
check(tp and tp.get('id') == 556 and tp.get('n') == 'Astral Recall' and tp.get('cd', {}).get('d') == 900,
      "Astral Recall known and cast: tp { id 556, n, cd d 900 }", tp)
n0 = nsent(L)
L.execute('COMBAT = true; CDS[556] = "secret"; fire("PLAYER_REGEN_DISABLED"); runTimers(NOW + 1); COMBAT = false')
tp = (last(L, n0, 'itemcd') or {}).get('tp')
check(tp and tp.get('cd', {}).get('d') == 900, "in a fight (secret): the teleport's countdown keeps running", tp)

# ---------------------------------------------------------------- 6. the snapshot (an app start) forces the doc
n0 = nsent(L)
L.execute('WowCompanion.snapshot("manual"); runTimers(NOW + 1)')
check(len(docs(L, n0, 'itemcd')) == 1, "a manual snapshot (the app's start) sends the itemcd again", len(docs(L, n0, 'itemcd')))
check(L.eval('ERRS[1]') is None, "no error anywhere", L.eval('ERRS[1]'))

print("\n%d passed, %d failed" % (passes, fails))
if fails == 0:
    print("ALL PASS")
sys.exit(1 if fails else 0)
