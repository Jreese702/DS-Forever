"""bags-unbind1 (issue #213, 1.0.27: "I cannot clear a binding made through the bag screen. I binded an item to left stick and can no
longer remove it."). Loads the real WowCompanion/Bags.lua in Lua 5.1 with stand-in game functions and drives the Bags room's
controller (Y > Bind to a Button > the capture's chord) to check:
  - a bind lands as before: our override on the chord, the record saved per character (WowCompanionDB.bagBinds[name-realm]);
  - the sheet opened again on the same item names its button (ctl.mine), and pressing that button clears it: note "unbound",
    the override let go (SetOverrideBinding(..., nil)), the record gone from the saved variables, the button's item attribute nil;
  - after a /reload (PLAYER_LOGIN) nothing comes back;
  - in a fight the clear is refused (bind-combat) and nothing changes;
  - another item's button pressed is still a REPLACE (as before), never a clear;
  - no trace: no SetBinding / SaveBindings / macro call on these routes.
Run: python -B tools/test_bag_unbind.py
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
HANDLERS, FRAMES = {}, {}
function CreateFrame(kind, name)
    local fr = setmetatable({ attrs = {}, name = name }, { __index = function() return stub end })
    function fr:SetScript(k, fn) if k == "OnEvent" then HANDLERS[#HANDLERS + 1] = fn end end
    function fr:SetAttribute(k, v) if COMBAT then error("blocked") end; self.attrs[k] = v end
    function fr:GetName() return self.name end
    if name then FRAMES[name] = fr end
    return fr
end
function fire(ev, ...) for _, fn in ipairs(HANDLERS) do pcall(fn, nil, ev, ...) end end
EMITS = {}
WowCompanion = {
    S = function(fn) local ok, v = pcall(fn); if ok then return v end end,
    SN = function(fn) local ok, v = pcall(fn); if ok and type(v) == "number" then return v end end,
    CALL = function(fn) return pcall(fn) end,
    emit = function(t) EMITS[#EMITS + 1] = t end,
    send = function() end, encode = function() return "" end, push = function() end, isPlain = function() return true end,
    pad = { secureRow = function() return 1 end, op = function() end },
}
COMBAT = false
function InCombatLockdown() return COMBAT end
function UnitName() return "Shamy" end
function GetRealmName() return "Forever" end
OVR, TRACE = {}, {}
function SetOverrideBindingClick(_, _, k, btn) if COMBAT then error("blocked") end; OVR[k] = "CLICK " .. btn .. ":LeftButton" end
function SetOverrideBinding(_, _, k, cmd) if COMBAT then error("blocked") end; OVR[k] = cmd end
function GetBindingAction(k, ovr) if ovr then return OVR[k] or "" end; return "" end
function SetBinding() TRACE[#TRACE + 1] = "SetBinding" end
function SaveBindings() TRACE[#TRACE + 1] = "SaveBindings" end
function DeleteMacro() TRACE[#TRACE + 1] = "DeleteMacro" end
function GetNumMacros() return 0, 0 end
BAG = { [0] = { [1] = 117, [2] = 118 } }
C_Container = { GetContainerItemID = function(b, s) return BAG[b] and BAG[b][s] end, GetContainerNumSlots = function(b) return b == 0 and 4 or 0 end,
                GetContainerItemInfo = function(b, s) if BAG[b] and BAG[b][s] then return { iconFileID = 1, quality = 1 } end end }
C_Item = { GetItemNameByID = function(id) return ({ [117] = "Tough Jerky", [118] = "Minor Healing Potion" })[id] end }
WowCompanion._bagsLayout = { { bag = 0, slots = 4 } }
for _, n in ipairs({ "MerchantFrame", "GossipFrame", "QuestFrame", "BankFrame", "MailFrame", "TradeFrame", "AuctionHouseFrame",
                     "GuildBankFrame", "ClassTrainerFrame", "CharacterFrame" }) do _G[n] = { IsShown = function() return false end } end
SPEC, CHORD = nil, nil
WowCompanion.capture = { version = 11, take = function(_, spec) SPEC = spec; return true end, pass = function() return true end,
                         release = function() return true end, state = function() return { holder = "bags" } end,
                         chord = function(_, done) CHORD = done; return true end, refused = function() return false end }
function press(k) SPEC.press(k) end
function doc() return WowCompanion._bagsCtlDoc() end
-- Y > Bind to a Button on the cursor's slot (the menu opens on its first usable row: a single item = Bind)
function openSheet() press("PAD4"); press("PAD1") end
function chord(k) local c = CHORD; CHORD = nil; c(k) end
''')
load = L.eval('function(src) local f, e = loadstring(src, "@Bags.lua"); if not f then error(e) end; return f end')
ok_load = True
try:
    load(SRC)()
except Exception as e:  # noqa: BLE001
    ok_load = False; print(e)
check(ok_load, "Bags.lua loads (stand-in game) with no error")
L.execute('WowCompanionDB = {}; fire("PLAYER_LOGIN"); WowCompanion._bagsTouch(29)')
check(L.eval('SPEC') is not None and L.eval('doc().on') is True, "the room took the controller")

# 1. the bind lands (as before)
L.execute('openSheet()')
d = L.eval('doc()')
check(d.mode == 'bind' and L.eval('CHORD') is not None, "Y > Bind: the sheet asks for a button", d.mode)
check(d.mine is None, "a fresh item: no own button on the sheet", d.mine)
L.execute('chord("PADLSTICK")')
d = L.eval('doc()')
check(d.note == 'bound' and d.bound == 'PADLSTICK', "bound to the left stick", (d.note, d.bound))
check(L.eval('OVR.PADLSTICK') == 'CLICK WowCompanionBagBind_PADLSTICK:LeftButton', "our override on PADLSTICK", L.eval('OVR.PADLSTICK'))
check(L.eval('WowCompanionDB.bagBinds["Shamy-Forever"].PADLSTICK') == 117, "the record saved per character")
check(L.eval('FRAMES.WowCompanionBagBind_PADLSTICK.attrs.item') == 'item:117', "the button holds the item")
check(d.bbinds is not None and d.bbinds.PADLSTICK == 117, "the doc lists the character's item binds (diagnostics)")
L.execute('press("PAD2")')
check(L.eval('doc().mode') == 'grid', "B closes the sheet")

# 2. the sheet again on the same item: it names its button; in a fight the clear is refused
L.execute('openSheet()')
d = L.eval('doc()')
check(d.mine is not None and d.mine[1] == 'PADLSTICK', "the sheet names the item's own button (ctl.mine)", d.mine and list(d.mine.values()))
L.execute('COMBAT = true; chord("PADLSTICK")')
d = L.eval('doc()')
check(d.note == 'bind-combat' and L.eval('OVR.PADLSTICK') is not None
      and L.eval('WowCompanionDB.bagBinds["Shamy-Forever"].PADLSTICK') == 117, "in a fight: bind-combat, nothing changed", d.note)
L.execute('COMBAT = false')
check(L.eval('CHORD') is not None, "after the refusal the sheet asks again")

# 3. the same button pressed again (out of combat): cleared
L.execute('chord("PADLSTICK")')
d = L.eval('doc()')
check(d.note == 'unbound' and d.unbound == 'PADLSTICK' and d.bound is None, "the item's own button again: unbound", (d.note, d.unbound))
check(L.eval('OVR.PADLSTICK') is None, "the override let go", L.eval('OVR.PADLSTICK'))
check(L.eval('WowCompanionDB.bagBinds["Shamy-Forever"]') is None, "the record gone from the saved variables")
check(L.eval('FRAMES.WowCompanionBagBind_PADLSTICK.attrs.item') is None, "the button holds no item")
keys = {k.k: k.a for k in d['keys'].values()} if d['keys'] else {}
check(keys.get('PADLSTICK') is None, "the sheet's chip for the left stick reads Free", keys)
check(d.bbinds is None and d.mine is None, "no item binds left", (d.bbinds, d.mine))
L.execute('press("PAD2")')

# 4. a /reload: nothing comes back
L.execute('fire("PLAYER_LOGIN")')
check(L.eval('OVR.PADLSTICK') is None, "after PLAYER_LOGIN the left stick stays free")

# 5. another item's button pressed is a replace, never a clear (as before)
L.execute('openSheet(); chord("PADRSTICK"); press("PAD2")')         # jerky -> right stick
L.execute('SPEC.press("PADDRIGHT")')                                # the cursor to slot 2 (the potion)
L.execute('openSheet(); chord("PADRSTICK")')
d = L.eval('doc()')
check(d.note == 'bound' and L.eval('WowCompanionDB.bagBinds["Shamy-Forever"].PADRSTICK') == 118,
      "the potion on the jerky's button: replaced (bound), not cleared", (d.note, L.eval('WowCompanionDB.bagBinds["Shamy-Forever"].PADRSTICK')))
L.execute('press("PAD2"); openSheet(); chord("PADRSTICK")')
check(L.eval('doc().note') == 'unbound' and L.eval('OVR.PADRSTICK') is None, "then the potion's own button again: cleared")

check(int(L.eval('#TRACE')) == 0, "no trace: no SetBinding / SaveBindings / DeleteMacro", list(L.eval('TRACE').values()))
print('ALL PASS (%d checks)' % passes if fails == 0 else '%d FAILED (of %d)' % (fails, fails + passes))
sys.exit(1 if fails else 0)
