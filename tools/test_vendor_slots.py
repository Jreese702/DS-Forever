"""bags-vslots1 (feat/vendor-bag-slots; the user, 2026-11-16, on the vendor window's bag / ammo figures: "It should show like bag slots out
of how many available and for ammo it should show ammo/total available space. The numbers on their own don't mean anything"). Loads the
REAL WowCompanion/Safety.lua, TapPad.lua and Bags.lua in Lua 5.1 (the shared stubbed game, tools/wowstub.py) with a bag bar of the
game's own shape and checks the add-on's half:
  - the worn ammo says its stack size (equipped.ammo.stack): C_Item.GetItemMaxStackSizeByID when the client has it, else GetItemInfo's
    stack count (C_Item's, else the global); unread = left out (the app's Classic 200), never a guess;
  - what the app adds up is in the doc as the encoder writes it: per bag its slots / free / family, the stacks' counts per bag;
  - no trace: no binding / macro / CVar / Blizzard window call.
The documents (cut to what VendorSlots reads) are dsforever's VendorSlotsTest fixtures (src/test/resources/vendor-fixtures): the Kotlin
side reads the add-on's own output, so the two halves cannot drift apart. PARTY_FIXTURES_WRITE=1 rewrites them; otherwise stale = FAIL.
Run: python -B tools/test_vendor_slots.py
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import ROOT, ADDONS, boot  # noqa: E402

BAGS = open(os.path.join(ADDONS, 'WowCompanion', 'Bags.lua'), encoding='utf-8').read()
FIXDIR = os.path.join(ROOT, 'dsforever', 'src', 'test', 'resources', 'vendor-fixtures')
FIX = {}
passes = fails = 0


def check(ok, what, got=None):
    global passes, fails
    if ok:
        passes += 1; print('PASS', what)
    else:
        fails += 1; print('FAIL', what, '->', got)


# THE BAG BAR: SLOTS[bag] = its size, FAM[bag] = the game's bag family, BAG[bag][slot] = { id, n }; WORN[inv] = an item id (0 = the ammo)
WORLD = r'''
SLOTS, FAM, BAG, WORN, ICON = {}, {}, {}, {}, {}
STACK = {}                           -- item id -> its stack size (GetItemInfo's 8th return); nil = not cached
MAXSTACK = nil                       -- nil = the client has no C_Item.GetItemMaxStackSizeByID
GLOBAL_INFO = false                  -- true = GetItemInfo only as the global (no C_Item.GetItemInfo)
local function info(id)
    if not STACK[id] then return nil end
    return "Item " .. id, "|Hitem:" .. id .. "|h", 1, 10, 1, "Projectile", "Arrow", STACK[id], "INVTYPE_AMMO", ICON[id] or 1, 1, 6, 2
end
C_Container = {
    GetContainerNumSlots = function(b) return SLOTS[b] or 0 end,
    GetContainerNumFreeSlots = function(b)
        local n = 0; for s = 1, SLOTS[b] or 0 do if not (BAG[b] and BAG[b][s]) then n = n + 1 end end; return n, FAM[b] or 0 end,
    GetContainerItemInfo = function(b, s) local x = BAG[b] and BAG[b][s]
        if x then return { itemID = x.id, stackCount = x.n, iconFileID = ICON[x.id] or 1, quality = 1, hyperlink = "|Hitem:" .. x.id .. "|h" } end end,
    GetContainerItemID = function(b, s) local x = BAG[b] and BAG[b][s]; return x and x.id end,
    GetContainerItemLink = function(b, s) local x = BAG[b] and BAG[b][s]; return x and ("|Hitem:" .. x.id .. "|h") end,
    ContainerIDToInventoryID = function(b) return 19 + b end,
}
C_Item = setmetatable({
    GetItemNameByID = function(id) return "Item " .. id end,
    GetItemInfoInstant = function(id) return id, "Projectile", "Arrow", "INVTYPE_AMMO", ICON[id] or 1, 6, 2 end,
    GetItemSpell = function() return nil end,
    RequestLoadItemDataByID = function() end,
}, { __index = function(_, k)
    if k == "GetItemMaxStackSizeByID" and MAXSTACK then return function(id) return MAXSTACK[id] end end
    if k == "GetItemInfo" and not GLOBAL_INFO then return info end
end })
function GetItemInfo(id) if GLOBAL_INFO then return info(id) end end
function GetInventoryItemID(_, slot) return WORN[slot] end
function GetInventoryItemTexture(_, slot) return WORN[slot] and (ICON[WORN[slot]] or 1) end
function GetInventoryItemQuality(_, slot) return WORN[slot] and 1 end
function GetInventoryItemCount(_, slot)
    local id = WORN[slot]; if not id then return 0 end
    local n = 0; for b, t in pairs(BAG) do for _, x in pairs(t) do if x.id == id then n = n + (x.n or 0) end end end; return n
end
function GetInventoryItemLink(_, slot) return WORN[slot] and ("|Hitem:" .. WORN[slot] .. "|h") end
function fill(bag, size, fam, stacks)                -- stacks: { {id, n}, ... } from slot 1
    SLOTS[bag] = size; FAM[bag] = fam; BAG[bag] = {}
    for s, x in ipairs(stacks or {}) do BAG[bag][s] = { id = x[1], n = x[2] } end
end
function clear() SLOTS, FAM, BAG, WORN = {}, {}, {}, {}; MAXSTACK = nil; GLOBAL_INFO = false end
'''

L = boot(['safety', 'tappad'], pre=WORLD)
load = L.eval('function(src, name, addon) local f, e = loadstring(src, "@" .. name); if not f then error(e) end; return f(addon) end')
try:
    load(BAGS, 'Bags.lua', 'WowCompanion'); ok_load = True
except Exception as e:  # noqa: BLE001
    ok_load = False; print(e)
check(ok_load, "Bags.lua loads (the stubbed game) with no error")


def collect():
    """the bags document as the encoder writes it (Safety.lua's WC.encode), parsed"""
    return json.loads(L.eval('WowCompanion.encode(WowCompanion.collectBags())'))


def fixture(name, d):
    """the doc cut to what VendorSlots reads (the rest of it moves with other rooms' work and is not this contract)"""
    keep = {'bags': [{k: b[k] for k in ('bag', 'slots', 'free', 'fam', 'reagent', 'icon', 'id') if k in b} for b in d.get('bags', [])],
            'items': [{k: r[k] for k in ('bag', 'slot', 'id', 'count') if k in r} for r in d.get('items', [])]}
    if 'freeGeneral' in d:
        keep['freeGeneral'] = d['freeGeneral']
    ammo = (d.get('equipped') or {}).get('ammo')
    if ammo is not None:
        keep['equipped'] = {'ammo': {k: ammo[k] for k in ('id', 'icon', 'count', 'stack') if k in ammo}}
    FIX[name] = json.dumps(keep, indent=1, sort_keys=True) + '\n'


ARROW, SHOT, WATER, LINEN = 2512, 2516, 159, 2589
L.execute('ICON[%d] = 132382; ICON[%d] = 132384; ICON[5439] = 134407; ICON[5441] = 133581; ICON[4496] = 133633; ICON[22243] = 133649' % (ARROW, SHOT))

# ---- 1. a hunter at a vendor: backpack 16 (10 used), a 14-slot bag (12 used), an 8-slot quiver with 200 + 200 + 150 Rough Arrows worn
L.execute('''clear()
fill(0, 16, 0, { {159, 5}, {2589, 12}, {159, 5}, {2589, 3}, {159, 2}, {2589, 1}, {159, 1}, {2589, 9}, {159, 4}, {2589, 2} })
fill(1, 14, 0, { {2589, 1}, {2589, 1}, {2589, 1}, {2589, 1}, {2589, 1}, {2589, 1}, {2589, 1}, {2589, 1}, {2589, 1}, {2589, 1}, {2589, 1}, {2589, 1} })
fill(2, 8, 1, { {2512, 200}, {2512, 200}, {2512, 150} })
WORN[20] = 4496; WORN[21] = 5439; WORN[0] = 2512; STACK[2512] = 200''')
L.execute('SPY = {}')
d = collect()
am = (d.get('equipped') or {}).get('ammo') or {}
check(am.get('stack') == 200, "the worn ammo's stack size from GetItemInfo (no GetItemMaxStackSizeByID): 200", am)
check(am.get('id') == ARROW and am.get('count') == 550, "...beside its id and the game's own count (550)", am)
qv = [b for b in d['bags'] if b.get('bag') == 2]
check(len(qv) == 1 and qv[0].get('fam') == 1 and qv[0].get('slots') == 8 and qv[0].get('free') == 5 and qv[0].get('icon') == 134407,
      "the quiver: family 1 (arrows), 8 slots, 5 free, its own icon", qv)
plain = [b for b in d['bags'] if b.get('fam') == 0]
check(sum(b['slots'] for b in plain) == 30 and sum(b['slots'] - b['free'] for b in plain) == 22, "the plain bags: 22 of 30 used", plain)
inq = [r for r in d['items'] if r.get('bag') == 2]
check(sorted(r.get('count') for r in inq) == [150, 200, 200], "the quiver's stacks each carry their count", inq)
check(d.get('freeGeneral') == 8, "freeGeneral unchanged (8: the quiver not in it)", d.get('freeGeneral'))
check(not list(L.eval('SPY').values()), "no trace: no binding / macro / CVar / window call", list(L.eval('SPY').values()))
fixture('bags_hunter', d)

# ---- 2. a client with C_Item.GetItemMaxStackSizeByID and 1000-arrow stacks: a 12-slot quiver, 1000 + 1000 + 340
L.execute('''clear()
fill(0, 16, 0, { {159, 5}, {2589, 2} })
fill(1, 12, 1, { {2512, 1000}, {2512, 1000}, {2512, 340} })
WORN[20] = 5439; WORN[0] = 2512; STACK[2512] = 200; MAXSTACK = { [2512] = 1000 }''')
am = collect()['equipped'].get('ammo') or {}
check(am.get('stack') == 1000, "GetItemMaxStackSizeByID first: 1000 (GetItemInfo's 200 not used)", am)
fixture('bags_hunter_1000', collect())

# ---- 3. only the global GetItemInfo (no C_Item.GetItemInfo): an ammo pouch of Light Shot, nearly empty
L.execute('''clear()
fill(0, 16, 0, { {159, 5}, {2589, 2}, {159, 5}, {2589, 2}, {159, 5}, {2589, 2}, {159, 5}, {2589, 2}, {159, 5}, {2589, 2}, {159, 5}, {2589, 2},
                 {159, 5}, {2589, 2}, {159, 5}, {2589, 2} })
fill(1, 10, 2, { {2516, 60} })
WORN[20] = 5441; WORN[0] = 2516; STACK[2516] = 200; GLOBAL_INFO = true''')
d = collect()
am = d['equipped'].get('ammo') or {}
check(am.get('stack') == 200, "the global GetItemInfo when C_Item has none: 200", am)
fixture('bags_full_pouch_low', d)

# ---- 4. the ammo's info not cached: no stack (the app falls back), never a made-up one
L.execute('clear(); fill(0, 16, 0, {}); fill(1, 8, 1, { {2512, 200} }); WORN[20] = 5439; WORN[0] = 2512; STACK[2512] = nil')
d = collect()
am = d['equipped'].get('ammo') or {}
check('stack' not in am and am.get('id') == ARROW, "the stack size not read: left out", am)
fixture('bags_hunter_stack_unread', d)
L.execute('STACK[2512] = 200')

# ---- 5. no ammo worn (a warlock): no ammo table; a soul bag and the backpack, the backpack full
L.execute('''clear()
fill(0, 16, 0, { {159, 5}, {2589, 2}, {159, 5}, {2589, 2}, {159, 5}, {2589, 2}, {159, 5}, {2589, 2}, {159, 5}, {2589, 2}, {159, 5}, {2589, 2},
                 {159, 5}, {2589, 2}, {159, 5}, {2589, 2} })
fill(1, 20, 4, { {6265, 1}, {6265, 1} })
WORN[20] = 22243''')
d = collect()
check((d.get('equipped') or {}).get('ammo') is None, "no ammo worn: no ammo in the doc", d.get('equipped', {}).get('ammo'))
fixture('bags_warlock_full', d)

# ---- 6. the empty quiver: 0 arrows, the ammo still worn (the game keeps the slot's item)
L.execute('clear(); fill(0, 16, 0, { {159, 5} }); fill(1, 8, 1, {}); WORN[20] = 5439; WORN[0] = 2512; STACK[2512] = 200')
d = collect()
check(d['equipped']['ammo'].get('stack') == 200 and d['equipped']['ammo'].get('count') == 0, "an empty quiver: count 0, stack 200",
      d['equipped']['ammo'])
fixture('bags_hunter_empty_quiver', d)

# ================================================================ the fixtures
os.makedirs(FIXDIR, exist_ok=True)
stale = []
for name, text in sorted(FIX.items()):
    fp = os.path.join(FIXDIR, name + '.json')
    old = open(fp, encoding='utf-8').read() if os.path.isfile(fp) else None
    if os.environ.get('PARTY_FIXTURES_WRITE') == '1':
        with open(fp, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(text)
    elif old != text:
        stale.append(name)
check(len(FIX) == 6 and not stale, "the committed fixtures equal what the add-on sends now (%d files; PARTY_FIXTURES_WRITE=1 rewrites)" % len(FIX),
      stale)

print('\n%d passed, %d failed' % (passes, fails))
sys.exit(1 if fails else 0)
