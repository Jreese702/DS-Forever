"""bags-unbind1 (issue #213, 1.0.27: "I cannot clear a binding made through the bag screen. I binded an item to left stick and can no
longer remove it.") and bags-bindbox1 (the user, 2026-11-13: "when doing bind to a button from bags it should open the normal spell
binding menu"). Loads the real WowCompanion/Bags.lua in Lua 5.1 with stand-in game functions and a stand-in Bind box (Book b59's
WC.spellsBindItem / WC.spellsBindHosted: tools/test_bags_bindbox.py drives the real one) and checks:
  - Y > Bind to a Button opens the Spellbook's Bind box for the cursor's item, hosted (Bags mode "bindbox"), never the old chord sheet;
  - Bags hands the box every press (D-pad, A / B / X / Y, L3 / R3, Select), the box's extra keys go to the capture under Bags' name;
  - the box's end (done) puts the room back on the grid on the same slot; a refused open = the grid, said; in a fight = bind-combat;
  - the controller lost / the room left with the box up closes the box (abort);
  - THE OLD BINDS (WowCompanionDB.bagBinds[name-realm]): RB / RT / Select ones are still applied at login (our override, the item
    button); an L3 / R3 one MOVES into PadMap's stick entry (putItem on that stick) and leaves the record; a stick that holds something
    already keeps it (ours goes); PadMap not ready = kept for later; after the move a /reload brings nothing back on the stick;
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


PRE = r'''
local stub
stub = setmetatable({}, { __index = function() return stub end, __call = function() return stub end })
setmetatable(_G, { __index = function(_, k) return stub end })
NOW = 100
function GetTime() return NOW end
TIMERS = {}
C_Timer = { NewTicker = function() return stub end, After = function(s, fn) TIMERS[#TIMERS + 1] = { at = NOW + s, fn = fn } end,
            NewTimer = function() return stub end }
function runTimers(upto)
    local again = true
    while again do
        again = false
        for i, t in ipairs(TIMERS) do if t.at <= upto then table.remove(TIMERS, i); pcall(t.fn); again = true; break end end
    end
end
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
function CreateMacro() TRACE[#TRACE + 1] = "CreateMacro" end
function GetNumMacros() return 0, 0 end
BAG = { [0] = { [1] = 117, [2] = 118 } }
C_Container = { GetContainerItemID = function(b, s) return BAG[b] and BAG[b][s] end, GetContainerNumSlots = function(b) return b == 0 and 4 or 0 end,
                GetContainerItemInfo = function(b, s) if BAG[b] and BAG[b][s] then return { iconFileID = 1, quality = 1 } end end }
C_Item = { GetItemNameByID = function(id) return ({ [117] = "Tough Jerky", [118] = "Minor Healing Potion" })[id] end }
WowCompanion._bagsLayout = { { bag = 0, slots = 4 } }
for _, n in ipairs({ "MerchantFrame", "GossipFrame", "QuestFrame", "BankFrame", "MailFrame", "TradeFrame", "AuctionHouseFrame",
                     "GuildBankFrame", "ClassTrainerFrame", "CharacterFrame" }) do _G[n] = { IsShown = function() return false end } end
SPEC, EXTRA = nil, nil
WowCompanion.capture = { version = 11, take = function(_, spec) SPEC = spec; return true end, pass = function() return true end,
                         release = function() return true end, state = function() return { holder = "bags" } end,
                         extra = function(guest, list) EXTRA = guest .. ":" .. table.concat(list, ","); return true end,
                         chord = function() TRACE[#TRACE + 1] = "chord"; return true end, refused = function() return false end }
-- the stand-in Bind box (Book b59's API): it records what Bags hands it
BOX = { opened = {}, presses = {}, aborts = {}, refuse = nil }
WowCompanion.spellsBindItem = function(id, host)
    if BOX.refuse then return false, BOX.refuse end
    BOX.opened[#BOX.opened + 1] = id; BOX.host = host; BOX.up = true
    host.extra({ "PADBACK", "PADLSTICK", "PADRSTICK" })
    return true
end
WowCompanion.spellsBindHosted = {
    up = function() return BOX.up == true end,
    press = function(k) if not BOX.up then return false end; BOX.presses[#BOX.presses + 1] = k; return true end,
    abort = function(why) if not BOX.up then return false end; BOX.up = nil; BOX.aborts[#BOX.aborts + 1] = why; return true end,
}
function boxDone(note) BOX.up = nil; BOX.host.extra({}); BOX.host.done(note) end
-- PadMap's half the old L3 / R3 binds move into (stickSaved / putItem / page)
STICKS, PUTS = {}, {}
function padMapOn()
    WowCompanion.padMap = {
        page = function() return 1 end,
        stickSaved = function(set, pos) return STICKS[set .. ":" .. pos] or {} end,
        putItem = function(page, set, pos, id) if rawget(_G, "PM_REFUSE") then return false, "bad item" end
            PUTS[#PUTS + 1] = page .. "/" .. set .. "/" .. pos .. "=" .. id; STICKS[set .. ":" .. pos] = { e = { t = "item", id = id } }; return true end,
    }
end
function press(k) SPEC.press(k) end
function doc() return WowCompanion._bagsCtlDoc() end
-- Y > Bind to a Button on the cursor's slot (the menu opens on its first usable row: a single item = Bind)
function openBox() press("PAD4"); press("PAD1") end
'''


def boot(db='{}', padmap=True):
    L = lua51.LuaRuntime(unpack_returned_tuples=True)
    L.execute(PRE)
    if padmap:
        L.execute('padMapOn()')
    load = L.eval('function(src) local f, e = loadstring(src, "@Bags.lua"); if not f then error(e) end; return f end')
    load(SRC)()
    L.execute('WowCompanionDB = %s; fire("PLAYER_LOGIN"); runTimers(NOW + 10); WowCompanion._bagsTouch(29)' % db)
    return L


try:
    L = boot()
    ok_load = True
except Exception as e:  # noqa: BLE001
    ok_load = False; print(e)
check(ok_load, "Bags.lua loads (stand-in game) with no error")
check(L.eval('SPEC') is not None and L.eval('doc().on') is True, "the room took the controller")

# 1. Y > Bind: the Bind box for the cursor's item, hosted
L.execute('openBox()')
d = L.eval('doc()')
check(d.mode == 'bindbox' and d.boxItem == 117 and L.eval('BOX.opened[1]') == 117, "Y > Bind: the Bind box opens for the jerky (bindbox)",
      (d.mode, d.boxItem))
check(L.eval('BOX.host.name') == "bags" and L.eval('EXTRA') == "bags:PADBACK,PADLSTICK,PADRSTICK",
      "the box's extra keys asked at the capture under Bags' name", L.eval('EXTRA'))
check(d['keys'] is None and d.mine is None and d.bound is None, "no old sheet fields in the doc", (d['keys'], d.mine))
for k in ("PADDUP", "PAD1", "PAD3", "PAD4", "PADLSTICK", "PADBACK", "PAD2"):
    L.execute('press("%s")' % k)
check(list(L.eval('BOX.presses').values()) == ["PADDUP", "PAD1", "PAD3", "PAD4", "PADLSTICK", "PADBACK", "PAD2"]
      and L.eval('doc().mode') == 'bindbox', "every press goes to the box (B too: the box backs out itself)", list(L.eval('BOX.presses').values()))
L.execute('boxDone("bind-cancel")')
d = L.eval('doc()')
check(d.mode == 'grid' and d.cur.bag == 0 and d.cur.slot == 1 and d.note == 'bind-cancel' and L.eval('EXTRA') == "bags:",
      "the box's end: the grid on the same slot, the extra keys given back", (d.mode, d.cur.slot, d.note, L.eval('EXTRA')))
L.execute('press("PADDLEFT")')
check(L.eval('#BOX.presses') == 7, "after the box: presses are the room's again")

# 2. by touch, from slot 2; the box refused; in a fight
L.execute('press("PADDRIGHT"); WowCompanion._bagsTouch(32); WowCompanion._bagsTouch(20)')
check(L.eval('doc().mode') == 'bindbox' and L.eval('BOX.opened[2]') == 118, "by touch (More, the Bind row): the box for the potion")
L.execute('WowCompanion._bagsTouch(33)')
check(L.eval('BOX.presses[#BOX.presses]') == "PAD2", "a finger's B goes to the box", L.eval('BOX.presses[#BOX.presses]'))
L.execute('boxDone("bind-cancel")')
check(L.eval('doc().mode') == 'grid' and L.eval('doc().cur.slot') == 2, "... then the grid, on slot 2")
L.execute('BOX.refuse = "no item"; openBox(); BOX.refuse = nil')
check(L.eval('doc().mode') == 'grid' and L.eval('doc().note') == 'bind-notready', "a box that would not open: the grid, bind-notready",
      L.eval('doc().note'))
L.execute('COMBAT = true; openBox()')
check(L.eval('doc().note') == 'bind-combat' and L.eval('#BOX.opened') == 2, "in a fight: bind-combat, no box", L.eval('doc().note'))
L.execute('COMBAT = false; press("PAD2")')

# 3. the controller lost / the room left with the box up: the box closes
L.execute('WowCompanion._bagsTouch(29); openBox()')
check(L.eval('BOX.up') is True, "the box up")
L.execute('SPEC.lost("taken")')
check(L.eval('BOX.up') is None and L.eval('BOX.aborts[1]') == "taken", "the controller lost: the box aborted", L.eval('BOX.aborts[1]'))
L.execute('WowCompanion._bagsTouch(29); openBox(); WowCompanion._bagsTouch(30)')
check(L.eval('BOX.up') is None and L.eval('#BOX.aborts') == 2, "the room left by touch: the box aborted", L.eval('BOX.aborts[2]'))
check(int(L.eval('#TRACE')) == 0, "no trace: no SetBinding / SaveBindings / macro call, no chord capture", list(L.eval('TRACE').values()))

# 4. THE OLD BINDS
OLD = '{ bagBinds = { ["Shamy-Forever"] = { PADLSTICK = 117, PADRSHOULDER = 118, PADRSTICK = 118 } } }'
M = boot(OLD)
check(M.eval('OVR.PADRSHOULDER') == 'CLICK WowCompanionBagBind_PADRSHOULDER:LeftButton'
      and M.eval('FRAMES.WowCompanionBagBind_PADRSHOULDER.attrs.item') == 'item:118', "an old RB bind is still applied (our override, the item)")
puts = sorted(M.eval('PUTS').values())
check(puts == ["1/1/10=118", "1/1/9=117"], "the old L3 / R3 binds MOVE into PadMap's stick entries (putItem on set 1, 9 / 10)", puts)
rec = M.eval('WowCompanionDB.bagBinds["Shamy-Forever"]')
check(rec is not None and rec.PADRSHOULDER == 118 and rec.PADLSTICK is None and rec.PADRSTICK is None,
      "the record keeps only RB", rec and dict(rec))
check(M.eval('OVR.PADLSTICK') is None and M.eval('OVR.PADRSTICK') is None, "our old stick overrides let go (the stick entry runs it now)")
M.execute('fire("PLAYER_LOGIN"); runTimers(NOW + 20)')
check(M.eval('OVR.PADLSTICK') is None and int(M.eval('#PUTS')) == 2, "a /reload: nothing comes back on the stick, no second move")

N = boot('{ bagBinds = { ["Shamy-Forever"] = { PADLSTICK = 117 } } }', padmap=False)
check(N.eval('WowCompanionDB.bagBinds["Shamy-Forever"].PADLSTICK') == 117 and N.eval('OVR.PADLSTICK') is not None,
      "PadMap not there yet: the old L3 bind is kept (and applied) for later")
N.execute('padMapOn(); STICKS["1:9"] = { bind = "TOGGLEAUTORUN" }; COMBAT = true; fire("PLAYER_REGEN_DISABLED"); COMBAT = false; fire("PLAYER_REGEN_ENABLED")')
check(N.eval('WowCompanionDB.bagBinds["Shamy-Forever"]') is None and int(N.eval('#PUTS')) == 0 and N.eval('OVR.PADLSTICK') is None,
      "a stick that holds something of its own: ours goes (it was hidden), nothing put", N.eval('#PUTS'))

P = boot('{ bagBinds = { ["Shamy-Forever"] = { PADRSTICK = 117 } } }', padmap=False)
P.execute('padMapOn(); PM_REFUSE = true; fire("PLAYER_LOGIN"); runTimers(NOW + 20)')
check(P.eval('WowCompanionDB.bagBinds["Shamy-Forever"].PADRSTICK') == 117, "PadMap refuses the put (the item not loaded): kept")
P.execute('PM_REFUSE = nil; fire("PLAYER_LOGIN"); runTimers(NOW + 20)')
check(P.eval('WowCompanionDB.bagBinds["Shamy-Forever"]') is None and list(P.eval('PUTS').values()) == ["1/1/10=117"],
      "... moved at the next try", list(P.eval('PUTS').values()))
check(int(M.eval('#TRACE')) + int(N.eval('#TRACE')) + int(P.eval('#TRACE')) == 0, "the old binds: no trace either")
print('ALL PASS (%d checks)' % passes if fails == 0 else '%d FAILED (of %d)' % (fails, fails + passes))
sys.exit(1 if fails else 0)
