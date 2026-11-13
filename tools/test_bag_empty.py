"""bags-empty1 (issue #29, 1.0.16 streaming: "bad argument #1 to '?' (Usage: local result = C_Item.IsEquippableItem(itemInfo))" from the
game's SecureTemplates.lua:433, self = Button <TapPad.lua:291>, actionType "item"; then the error frame took the gamepad UI's focus and
"WowCompanion has been blocked" - SetPreferredGamepadInteractTarget()). Loads the real WowCompanion/Bags.lua in Lua 5.1 with stand-in
game functions and checks the secure "item" cells of the bag rows and of binv (worn gear):
  - a click on an EMPTY slot's cell has no secure action for that click (PreClick: type nil), and "item" is back after it (PostClick);
  - a FILLED slot's cell keeps type "item" through the click (exactly as before);
  - in a fight nothing is changed (attributes are locked), and the tap proof says emptyFight; out of a fight it says empty;
  - the cells' attributes are otherwise as before (type item, item "<bag> <slot>" / "<slot>").
Run: python -B tools/test_bag_empty.py
"""
import os
import sys
from lupa import lua51

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = open(os.path.join(ROOT, 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons', 'WowCompanion', 'Bags.lua'),
           encoding='utf-8').read()
passes = fails = 0


def check(ok, what, got=None):
    global passes, fails
    if ok:
        passes += 1; print('PASS', what)
    else:
        fails += 1; print('FAIL', what, '->', got)


L = lua51.LuaRuntime(unpack_returned_tuples=True)
L.execute(r'''
local stub
stub = setmetatable({}, { __index = function() return stub end, __call = function() return stub end })
setmetatable(_G, { __index = function(_, k) return stub end })
NOW = 100
function GetTime() return NOW end
C_Timer = { NewTicker = function() return stub end, After = function() end, NewTimer = function() return stub end }
EMITS, SECURE = {}, {}
WowCompanion = {
    S = function(fn) local ok, v = pcall(fn); if ok then return v end end,
    SN = function(fn) local ok, v = pcall(fn); if ok and type(v) == "number" then return v end end,
    CALL = function(fn) return pcall(fn) end,
    emit = function(t) EMITS[#EMITS + 1] = t end,
    send = function() end, encode = function() return "" end, push = function() end, isPlain = function() return true end,
    pad = {
        secureRow = function(name, n, init, onClick) SECURE[name] = { n = n, init = init, onClick = onClick }; return 1 end,
        op = function() end,
    },
}
COMBAT = false
function InCombatLockdown() return COMBAT end
WORN = { [5] = 2580 }                                  -- the chest worn, the neck (2) empty
BAG = { [0] = { [1] = 117 } }                          -- bag 0: slot 1 jerky, slot 2 empty
function GetInventoryItemID(_, slot) return WORN[slot] end
C_Container = { GetContainerItemID = function(b, s) return BAG[b] and BAG[b][s] end, GetContainerNumSlots = function(b) return b == 0 and 4 or 0 end,
                GetContainerItemInfo = function(b, s) if BAG[b] and BAG[b][s] then return { iconFileID = 1, quality = 1 } end end }
-- a secure cell as the game has one: attributes (locked in a fight), hooked PreClick / PostClick, the action the click would run
function newCell()
    local c = { a = {}, hooks = {} }
    function c:SetAttribute(k, v) if COMBAT then error("ADDON_ACTION_BLOCKED SetAttribute") end; self.a[k] = v end
    function c:GetAttribute(k) return self.a[k] end
    function c:HookScript(k, fn) self.hooks[k] = self.hooks[k] or {}; table.insert(self.hooks[k], fn) end
    return c
end
-- one press of a cell: PreClick hooks, the secure action's type at that moment, PostClick hooks
function click(c)
    for _, fn in ipairs(c.hooks.PreClick or {}) do fn(c, "LeftButton", true) end
    local acted = c.a.type
    for _, fn in ipairs(c.hooks.PostClick or {}) do fn(c, "LeftButton", true) end
    return acted
end
''')
load = L.eval('function(src) local f, e = loadstring(src, "@Bags.lua"); if not f then error(e) end; return f end')
ok_load = True
try:
    load(SRC)()
except Exception as e:  # noqa: BLE001
    ok_load = False; print(e)
check(ok_load, "Bags.lua loads (stand-in game) with no error")

L.execute('WowCompanion.bagsPadRows({ { bag = 0, slots = 4 } })')
check(L.eval('SECURE.bag0') is not None, "the bag row bag0 is registered")
L.execute('''FULL, EMPTY = newCell(), newCell()
SECURE.bag0.init(FULL, 0); SECURE.bag0.init(EMPTY, 1)''')
check(L.eval('FULL.a.type') == 'item' and L.eval('FULL.a.item') == '0 1' and L.eval('EMPTY.a.item') == '0 2',
      "the bag cells as before: type item, item \"<bag> <slot>\"", (L.eval('FULL.a.item'), L.eval('EMPTY.a.item')))
check(L.eval('click(FULL)') == 'item' and L.eval('FULL.a.type') == 'item', "a filled slot: the click is the game's item action")
check(L.eval('click(EMPTY)') is None, "an empty slot: no secure action for that click (nothing raises IsEquippableItem(nil))")
check(L.eval('EMPTY.a.type') == 'item', "after the click the empty cell is type item again (a slot filled in a fight still works)")
L.execute('SECURE.bag0.onClick(1)')
e = L.eval('EMITS[#EMITS].bagsTap')
check(e is not None and e.empty is True and e.slot == 2 and e.emptyFight is None, "the tap proof says empty", e and dict(e))
L.execute('SECURE.bag0.onClick(0)')
e = L.eval('EMITS[#EMITS].bagsTap')
check(e.empty is None and e.emptyFight is None, "a filled slot's proof carries no empty flag", dict(e))
# re-running init (a bag changed size) does not stack the hooks
L.execute('SECURE.bag0.init(EMPTY, 1)')
check(int(L.eval('#EMPTY.hooks.PreClick')) == 1 and int(L.eval('#EMPTY.hooks.PostClick')) == 1, "one PreClick / PostClick hook per cell")

# in a fight: nothing changes (attributes are locked), the proof says emptyFight
L.execute('COMBAT = true; NOW = NOW + 5')
ok_fight = True
try:
    acted = L.eval('click(EMPTY)')
except Exception as e:  # noqa: BLE001
    ok_fight = False; acted = str(e)
check(ok_fight and acted == 'item', "in a fight: no attribute write (it would be blocked); the action is the game's", acted)
L.execute('SECURE.bag0.onClick(1)')
e = L.eval('EMITS[#EMITS].bagsTap')
check(e.emptyFight is True and e.empty is None and e.combat is True, "the fight's proof says emptyFight", dict(e))
L.execute('COMBAT = false; NOW = NOW + 5')

# worn gear (binv): the empty neck vs the worn chest
L.execute('NECK, CHEST = newCell(), newCell(); SECURE.binv.init(NECK, 1); SECURE.binv.init(CHEST, 4)')
check(L.eval('NECK.a.item') == '2' and L.eval('CHEST.a.item') == '5', "binv cells as before: item = the slot")
check(L.eval('click(NECK)') is None and L.eval('NECK.a.type') == 'item', "an empty worn slot: no action for the click, item again after")
check(L.eval('click(CHEST)') == 'item', "a worn item: the game's item action")
L.execute('NOW = NOW + 0.1; click(NECK); SECURE.binv.onClick(1)')
e = L.eval('EMITS[#EMITS].kitTap')
check(e is not None and e.empty is True and e.slot == 2, "the kit tap proof says empty", e and dict(e))
# the stop cell and the bag-gear macro cells are not touched
L.execute('STOP = newCell(); SECURE.binv.init(STOP, 19); G = newCell(); SECURE.binv.init(G, 20)')
check(L.eval('STOP.hooks.PreClick') is None and L.eval('G.hooks.PreClick') is None, "macro cells: no guard")

import re
CODE = chr(10).join(re.sub(r'--.*$', '', ln) for ln in SRC.splitlines())
check('seterrorhandler' not in CODE and 'CreateMacro' not in CODE, "no macro, no error-handler swap")

print('ALL PASS (%d checks)' % passes if fails == 0 else '%d FAILED (of %d)' % (fails, fails + passes))
sys.exit(1 if fails else 0)
