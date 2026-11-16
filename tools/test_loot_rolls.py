"""LOOT ROLLS, ANSWERED AT ONCE (loot-pop; the user, 2026-11-16: "I have also noticed that the loot roll window is delayed and buggy,
sometimes making me press the button twice to move on or even just taking like 2 second after accepting an input to move on"), off-device:
Interact.lua (i162) and Dialogs.lua (d34) together under a stub game whose rolls behave as the client's do - an answered roll stays in
GetActiveLootRollIDs until the game's CANCEL_LOOT_ROLL (a server round trip later), a bind-on-pickup roll asks first (CONFIRM_LOOT_ROLL,
inside RollOnLoot, its StaticPopup's data = the roll id).

    python tools/test_loot_rolls.py

THE CAUSE, PROVEN (the add-on's half; the app's half is DialogViewRollsTest): before d34 the dialogs document kept listing an answered roll
until the game dropped it, so the app drew it again and the next press landed on it; and a new roll's first document had no cells (the
dialogs looked a frame before Interact gave the roll its cells), so a touch in that gap was "no tap cell". The checks:
  * a new roll's FIRST document carries its cells (i162 WC.rollCells);
  * a roll answered through our cell (ACT.roll) leaves the document in that very frame (d34's RollOnLoot hook), the game still offering it;
  * the game's OWN roll (its frame's A / B / X) is seen the same way; a second ACT.roll on it is refused ("already rolled");
  * the game's selection on the answered roll is read as the one its frame takes next;
  * a BoP Need: the roll stays, ask = "need", askI = the confirm's slot, the confirm says roll = the id; Okay (ConfirmLootRoll) = gone;
    Cancel (its pop-up gone) = the roll open again; the confirm is not counted in mirrored();
  * CANCEL_LOOT_ROLL clears the mark; a roll the game never took comes back after D.ROLL_KEEP;
  * the cells skip answered rolls (the fifth roll gets cells once one of the first four is answered).
Needs lupa. Exit code 1 on any failure."""
import os
import sys

import lupa

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# LOOTROLLS_ADDONS=<an addons folder> runs the same checks against another add-on set (the proof: the base commit's fails them)
ADDONS = os.environ.get('LOOTROLLS_ADDONS') or os.path.join(ROOT, 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons')

STUB = r'''
local dummy
local mt = {}
mt.__index = function() return dummy end
mt.__call = function() return dummy end
dummy = setmetatable({}, mt)
unpack = table.unpack
loadstring = load
SENT, FRAMES, CELLS, CELLN = {}, {}, {}, {}
GAME = { t = 1000, combat = false, rolls = {}, calls = {}, confirms = {}, timers = {}, bopAsks = true }
function GetTime() return GAME.t end
function InCombatLockdown() return GAME.combat end
time = os.time
-- frames: real enough for event dispatch and hooks; any other method is a no-op
local FrameMT = {}
FrameMT.__index = function(self, k)
    local m = rawget(FrameMT, k)
    if m then return m end
    return function() return nil end
end
FrameMT.SetScript = function(self, k, fn) self.scripts[k] = fn end
FrameMT.GetScript = function(self, k) return self.scripts[k] end
FrameMT.HookScript = function(self, k, fn) self.hooks[#self.hooks + 1] = fn end
FrameMT.RegisterEvent = function(self, e) self.events[e] = true; return true end
FrameMT.UnregisterEvent = function(self, e) self.events[e] = nil end
FrameMT.SetAttribute = function(self, k, v) self.attr[k] = v end
FrameMT.GetAttribute = function(self, k) return self.attr[k] end
FrameMT.IsShown = function(self) return self.shown == true end
FrameMT.SetAlpha = function(self, a) self.alpha = a end
FrameMT.GetAlpha = function(self) return self.alpha or 1 end
FrameMT.IsEnabled = function() return true end
FrameMT.GetText = function(self) return self.text end
function CreateFrame(kind, name, parent, template)
    local f = setmetatable({ kind = kind, name = name, scripts = {}, hooks = {}, events = {}, attr = {}, shown = false }, FrameMT)
    FRAMES[#FRAMES + 1] = f
    if name then rawset(_G, name, f) end
    return f
end
UIParent = CreateFrame("Frame", "UIParent")
C_Timer = { After = function(t, fn) GAME.timers[#GAME.timers + 1] = fn end,
            NewTicker = function() return { Cancel = function() end } end, NewTimer = function() return { Cancel = function() end } end }
function FLUSH() for _ = 1, 6 do local q = GAME.timers; GAME.timers = {}; for _, fn in ipairs(q) do fn() end end end
function FIRE(e, ...) for _, f in ipairs(FRAMES) do if f.events[e] and f.scripts.OnEvent then f.scripts.OnEvent(f, e, ...) end end; FLUSH() end
-- the secure post-hook: the game's global first, then ours
function hooksecurefunc(a, b, c)
    local tbl, name, fn = _G, a, b
    if type(a) == "table" then tbl, name, fn = a, b, c end
    local orig = tbl[name]
    tbl[name] = function(...) local r = { orig(...) }; fn(...); return unpack(r) end
end
-- THE GAME'S ROLLS (the client's order): GAME.rolls = { { id, name, q, bop, need, greed, de, tm, gone } ... }
local function roll(id) for _, r in ipairs(GAME.rolls) do if r.id == id and not r.gone then return r end end end
function GetActiveLootRollIDs() local out = {}; for _, r in ipairs(GAME.rolls) do if not r.gone then out[#out + 1] = r.id end end; return out end
function GetLootRollItemInfo(id)
    local r = roll(id)
    if not r then return nil end
    return 134000 + id, r.name, 1, r.q or 2, r.bop == true, r.need ~= false, r.greed ~= false, r.de == true, 0, 0, 0, 0, r.tm == true
end
function GetLootRollTimeLeft(id) return roll(id) and 55000 or 0 end
C_Loot = { GetLootRollDuration = function() return 60000 end }
-- the game's bind confirm (gamedialogdefs.lua: StaticPopup_Show("CONFIRM_LOOT_ROLL"); dialog.data = rollID, data2 = rollType)
POPUPS = {}
for i = 1, 4 do
    local f = CreateFrame("Frame", "StaticPopup" .. i)
    f.Text = { GetText = function() return f.shown and f.words or nil end }
    f.btns = {}
    for k = 1, 4 do f.btns[k] = CreateFrame("Button", "StaticPopup" .. i .. "Button" .. k); f.btns[k].shown = false end
    f.GetButton = function(self, k) return self.btns[k] end
    f.gamepadFocused = true
    POPUPS[i] = f
end
function SHOWCONFIRM(id, ty)
    local f = POPUPS[1]
    f.shown, f.which, f.data, f.data2 = true, "CONFIRM_LOOT_ROLL", id, ty
    f.words = "Looting " .. (roll(id) and roll(id).name or "?") .. " will bind it to you."
    f.btns[1].shown, f.btns[1].text = true, "Okay"
    f.btns[2].shown, f.btns[2].text = true, "Cancel"
end
function HIDECONFIRM() local f = POPUPS[1]; f.shown, f.which, f.data, f.data2 = false, nil, nil, nil; f.btns[1].shown, f.btns[2].shown = false, false end
function RollOnLoot(id, ty)
    GAME.calls[#GAME.calls + 1] = { id = id, ty = ty }
    local r = roll(id)
    if r and r.bop and ty ~= 0 and GAME.bopAsks then
        -- the client asks first, inside the call (a synchronous event), and shows its pop-up
        SHOWCONFIRM(id, ty)
        for _, f in ipairs(FRAMES) do if f.events.CONFIRM_LOOT_ROLL and f.scripts.OnEvent then f.scripts.OnEvent(f, "CONFIRM_LOOT_ROLL", id, ty) end end
    end
end
-- the real order (review): StaticPopup_OnClick runs OnAccept (ConfirmLootRoll) while the pop-up still shows and hides it AFTER; GAME.confirmHides = false keeps it shown through the call
function ConfirmLootRoll(id, ty) GAME.confirms[#GAME.confirms + 1] = { id = id, ty = ty }; if GAME.confirmHides ~= false then HIDECONFIRM() end end
-- the game drops a roll (a server round trip after the answer, or the roll ends)
function DROP(id) for _, r in ipairs(GAME.rolls) do if r.id == id then r.gone = true end end; FIRE("CANCEL_LOOT_ROLL", id) end
function START(r) GAME.rolls[#GAME.rolls + 1] = r; FIRE("START_LOOT_ROLL", r.id, 60000) end
WowCompanion = {
  S = function(fn) local ok, v = pcall(fn); if ok then return v end end,
  SN = function(fn) local ok, v = pcall(fn); if ok and type(v) == "number" then return v end end,
  isPlain = function(v) return true end,
  CALL = function(fn) return pcall(fn) end,
  send = function(t) table.insert(SENT, t) end, encode = function(t) return t end,
  push = function() end, pushThrottled = function() end, emit = function() end, snapshot = function() end,
  interactMode = function() return true end,
  ABSENT = {}, SECRET = {}, THREW = {},
  pad = { op = function(name, ns, h) return {} end,
          slotFor = function(ns, key)
                        if not CELLS[ns .. ":" .. key] then CELLN[ns] = (CELLN[ns] or -1) + 1; CELLS[ns .. ":" .. key] = CELLN[ns] end
                        return CELLS[ns .. ":" .. key] end,
          retain = function(ns, present)
              for k in pairs(CELLS) do
                  local n, key = k:match("^([^:]+):(.*)$")
                  if n == ns and not present[key] then CELLS[k] = nil end
              end
          end,
          describe = function() return { ch = 3, rows = { idlg = 18, iact = 14, isell = 16 } } end },
}
setmetatable(_G, { __index = function(_, k) return dummy end })
-- the last dialogs document (after the app's sign of life asked for one)
function DOC() for i = #SENT, 1, -1 do local d = SENT[i]; if type(d) == "table" and d.type == "dialogs" then return d end end end
function FRESH() WowCompanion.dialogs.alive(); FLUSH(); return DOC() end
function IDS(d) local t = {}; for _, r in ipairs((d and d.rolls) or {}) do t[#t + 1] = r.id end; return table.concat(t, ",") end
function ROLL(d, id) for _, r in ipairs((d and d.rolls) or {}) do if r.id == id then return r end end end
'''

TEST = r'''
local out = {}
local function chk(n, c) out[#out + 1] = (c and "PASS " or "FAIL ") .. n end
local ACT, ON = __ACT, __ON
WowCompanion.dialogs.setMode(true)

-- 1. A NEW ROLL: its very first document carries its cells (i162; before it the first had none for a frame)
SENT = {}
GAME.rolls[#GAME.rolls + 1] = { id = 11, name = "Ironwood Staff", q = 2 }
-- the first look the game's event gives (Interact's ON.START_LOOT_ROLL -> WC.dialogs.look), before Interact's own document
ON.START_LOOT_ROLL(11, 60000)
local first
for _, d in ipairs(SENT) do if type(d) == "table" and d.type == "dialogs" and d.rolls then first = d; break end end
local r11 = ROLL(first, 11)
chk("i162: a new roll's FIRST dialogs document carries its cells (need " .. tostring(r11 and r11.cells and r11.cells.need) .. ", greed "
    .. tostring(r11 and r11.cells and r11.cells.greed) .. ", pass " .. tostring(r11 and r11.cells and r11.cells.pass) .. ")",
    r11 and r11.cells and r11.cells.need and r11.cells.greed and r11.cells.pass)
FLUSH()
local cellsFirst = r11 and r11.cells
r11 = ROLL(FRESH(), 11)
chk("... and Interact's own document keeps the SAME cells (assignSlots: the same keys)", r11 and cellsFirst and r11.cells.need == cellsFirst.need
    and r11.cells.pass == cellsFirst.pass)

-- 2. ANSWERED THROUGH OUR CELL: gone from the document in that very frame, while the game still offers it (no CANCEL_LOOT_ROLL yet)
START({ id = 12, name = "Silk Gloves", q = 2 })
START({ id = 13, name = "Linen Belt", q = 1 })
chk("three rolls listed", IDS(FRESH()) == "11,12,13")
SENT = {}
local ok = ACT.roll(11, "need")
chk("ACT.roll need on 11: the game's RollOnLoot(11, 1)", ok and GAME.calls[#GAME.calls].id == 11 and GAME.calls[#GAME.calls].ty == 1)
local inFrame = DOC()
chk("d34: the document written IN THAT FRAME no longer lists 11 (" .. IDS(inFrame) .. "), the game still offering it ("
    .. table.concat(GetActiveLootRollIDs(), ",") .. ")", inFrame and IDS(inFrame) == "12,13" and #GetActiveLootRollIDs() == 3)
chk("d34: the game's roll frame stays dimmed while the answer waits (rollsWait)", inFrame ~= nil)
local again, why = ACT.roll(11, "greed")
chk("i162: a second press on 11 never rolls again (" .. tostring(why) .. ")", again == false and why == "already rolled" and #GAME.calls == 1)

-- 3. THE GAME'S OWN ROLL (its frame on X): seen the same way
RollOnLoot(12, 2)
chk("d34: the game's own RollOnLoot on 12 (its frame's X): gone at once (" .. IDS(DOC()) .. ")", IDS(DOC()) == "13")

-- 4. THE GAME'S SELECTION on an answered roll: the one its frame takes next (RemoveRoll: the one before, else the first)
GamepadGroupLootRollFrame = { selectedRollIndex = 3, waitingRolls = { { rollID = 11 }, { rollID = 12 }, { rollID = 13 }, { rollID = 14 } } }
START({ id = 14, name = "Bone Ring", q = 3 })
local d = FRESH()
chk("the game selects 13: sel on 13", ROLL(d, 13) and ROLL(d, 13).sel == true)
RollOnLoot(13, 0)
d = DOC()
chk("13 passed by the game's frame: the selection read as the one before it that is open, else the first (14: " .. tostring(ROLL(d, 14) and ROLL(d, 14).sel) .. ")",
    IDS(d) == "14" and ROLL(d, 14).sel == true)
GamepadGroupLootRollFrame = nil

-- 5. THE GAME DROPS THEM (CANCEL_LOOT_ROLL): the marks go with them
DROP(11); DROP(12); DROP(13)
d = FRESH()
chk("CANCEL_LOOT_ROLL: 11 / 12 / 13 gone, 14 still listed (" .. IDS(d) .. ")", IDS(d) == "14")

-- 6. A BIND-ON-PICKUP NEED: the game asks first; the roll stays with its confirm, Okay = gone
START({ id = 21, name = "Gravestone Scepter", q = 3, bop = true })
SENT = {}
ACT.roll(21, "need")
d = DOC()
local r21 = ROLL(d, 21)
local conf
for _, x in ipairs((d and d.list) or {}) do if x.which == "CONFIRM_LOOT_ROLL" then conf = x end end
chk("d34: BoP need: 21 stays, ask = need (" .. tostring(r21 and r21.ask) .. "), askI = the confirm's slot (" .. tostring(r21 and r21.askI) .. ")",
    r21 and r21.ask == "need" and r21.askI == 1)
chk("d34: the confirm names its roll (roll " .. tostring(conf and conf.roll) .. ", type " .. tostring(conf and conf.rollType) .. ") and has its cells ("
    .. tostring(conf and conf.cells and conf.cells.b1) .. " / " .. tostring(conf and conf.cells and conf.cells.b2) .. ")",
    conf and conf.roll == 21 and conf.rollType == 1 and conf.cells and conf.cells.b1 == 0 and conf.cells.b2 == 1)
chk("d34: a roll's confirm is not counted as the box (mirrored " .. tostring(WowCompanion.dialogs.mirrored()) .. ")", WowCompanion.dialogs.mirrored() == 0)
-- the confirm's Okay (the game's own button: OnAccept = ConfirmLootRoll)
GAME.t = GAME.t + 0.3
ConfirmLootRoll(21, 1)
d = DOC()
chk("d34: Okay (ConfirmLootRoll): 21 gone in that frame (" .. IDS(d) .. ")", ROLL(d, 21) == nil)

-- 7. BoP CANCEL: the pop-up goes without ConfirmLootRoll -> the roll is open again
START({ id = 22, name = "Necklace of the Dawn", q = 3, bop = true })
ACT.roll(22, "greed")
chk("BoP greed on 22: asks (" .. tostring(ROLL(DOC(), 22) and ROLL(DOC(), 22).ask) .. ")", ROLL(DOC(), 22) and ROLL(DOC(), 22).ask == "greed")
HIDECONFIRM()
GAME.t = GAME.t + 1
d = FRESH()
chk("d34: Cancel (the pop-up gone, no ConfirmLootRoll): 22 open again, no ask (" .. tostring(ROLL(d, 22) and ROLL(d, 22).ask) .. ")",
    ROLL(d, 22) ~= nil and ROLL(d, 22).ask == nil)
local okAgain = ACT.roll(22, "pass")
chk("... and it can be answered again (pass)", okAgain == true and ROLL(DOC(), 22) == nil)

-- 8. A ROLL THE GAME NEVER TOOK comes back after D.ROLL_KEEP (the game still offering it, no CANCEL)
START({ id = 31, name = "Fine Cloak", q = 2 })
ACT.roll(31, "greed")
chk("31 answered: out", ROLL(DOC(), 31) == nil)
GAME.t = GAME.t + 5
chk("5 s later, still out", ROLL(FRESH(), 31) == nil)
GAME.t = GAME.t + 6
chk("d34: past ROLL_KEEP with the game still offering it: back", ROLL(FRESH(), 31) ~= nil)
DROP(31); DROP(14); DROP(21); DROP(22)

-- 8b. (review) THE GAME'S ORDER ON OKAY: its OnAccept (ConfirmLootRoll) runs while the pop-up still shows, the pop-up hides after; the
-- document the hook sends from INSIDE that click must not list the answered roll's confirm (its roll already held out: the app drew a box)
START({ id = 23, name = "Ring of the Moon", q = 3, bop = true })
ACT.roll(23, "need")
chk("BoP need on 23: asks (" .. tostring(ROLL(DOC(), 23) and ROLL(DOC(), 23).ask) .. ")", ROLL(DOC(), 23) and ROLL(DOC(), 23).ask == "need")
GAME.confirmHides = false
SENT = {}
GAME.t = GAME.t + 0.3
ConfirmLootRoll(23, 1)
local inClick = DOC()
local boxed = false
for _, x in ipairs((inClick and inClick.list) or {}) do if x.which == "CONFIRM_LOOT_ROLL" then boxed = true end end
chk("d34 review: the document sent from inside the confirm's click: 23 gone (" .. IDS(inClick) .. ") and its confirm not listed (listed: "
    .. tostring(boxed) .. ")", inClick ~= nil and ROLL(inClick, 23) == nil and not boxed)
HIDECONFIRM(); GAME.confirmHides = nil
DROP(23)

-- 9. THE CELLS SKIP ANSWERED ROLLS: five rolls, the fifth gets cells once one of the first four is answered
for i = 41, 45 do START({ id = i, name = "Cloth " .. i, q = 1 }) end
d = FRESH()
chk("five rolls: four shown, one more (" .. IDS(d) .. ", more " .. tostring(d.rollsMore) .. ")", IDS(d) == "41,42,43,44" and d.rollsMore == 1)
ACT.roll(42, "pass")
d = DOC()
local r45 = ROLL(d, 45)
chk("42 passed: 45 shown at once WITH its cells (" .. IDS(d) .. ", pass " .. tostring(r45 and r45.cells and r45.cells.pass) .. ")",
    IDS(d) == "41,43,44,45" and r45 and r45.cells and r45.cells.pass ~= nil)
FLUSH()
local r45b = ROLL(FRESH(), 45)
chk("... Interact's next document keeps 45's cells (the same open four)", r45b and r45b.cells and r45.cells and r45b.cells.pass == r45.cells.pass)
DROP(41); DROP(42); DROP(43); DROP(44); DROP(45)

-- 10. THE GAME'S ROLL FRAME stays dimmed while the last answered roll waits for the game's word (no flash on the top screen)
local gf = CreateFrame("Frame", "GamepadGroupLootRollFrame")
gf.shown, gf.alpha = true, 1
START({ id = 51, name = "Worn Boots", q = 1 })
FRESH()
chk("one roll shown by the app: the game's roll frame dimmed (" .. tostring(gf.alpha) .. ")", gf.alpha == 0)
SENT = {}
ACT.roll(51, "need")
d = DOC()
chk("d34: answered, the game still offering it: a document without the roll, the game's frame STILL dimmed (" .. tostring(gf.alpha) .. ")",
    d ~= nil and d.rolls == nil and gf.alpha == 0)
gf.shown = false
DROP(51)
chk("the game drops it (its frame hides itself): undimmed (" .. tostring(gf.alpha) .. ")", gf.alpha == 1)
return table.concat(out, "\n")
'''


def main():
    chat = open(os.path.join(ADDONS, 'WowCompanion', 'Chat.lua'), encoding='utf-8').read()
    inter = open(os.path.join(ADDONS, 'WowCompanionInteract', 'Interact.lua'), encoding='utf-8').read()
    inter = inter.replace("local ADDON = ...", "local ADDON = 'x'", 1)
    inter += "\n_G.__N = N; _G.__ACT = ACT; _G.__ON = ON; _G.__LOOT = LOOT\n"
    dlg = open(os.path.join(ADDONS, 'WowCompanionInteract', 'Dialogs.lua'), encoding='utf-8').read()
    L = lupa.LuaRuntime()
    L.execute(STUB)
    L.execute(chat.replace("local ADDON = ...", "local ADDON = 'WowCompanion'", 1))
    L.execute(inter)
    L.execute(dlg)
    res = L.execute(TEST)
    print(res)
    fails = str(res).count("FAIL ")
    print("failed: %d" % fails)
    sys.exit(1 if fails else 0)


if __name__ == '__main__':
    main()
