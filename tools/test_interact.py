"""Interact.lua (with Chat.lua) under stub game functions, off-device: runs a test script from tools/interact_tests.

    python tools/test_interact.py [t_name.lua ...]      (default: every t_*.lua there)

Each test script returns its PASS / FAIL lines. Needs lupa. Exit code 1 on any failure."""
import lupa, sys, os
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
addons = os.path.join(ROOT, 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons')
HERE = os.path.join(ROOT, 'tools', 'interact_tests')
STUB = r'''
local dummy
local mt = {}
mt.__index = function() return dummy end
mt.__call = function() return dummy end
dummy = setmetatable({}, mt)
unpack = table.unpack
loadstring = load
SENT, ROWS, CELLS = {}, {}, {}
local cellN = 0
-- the game's mail, as far as the tests need it
GAME = { money = 100000, price = 30, items = {}, bags = {}, cursor = nil, sent = nil, cod = 0, sendMoney = 0 }
function GetMoney() return GAME.money end
function GetSendMailPrice() return GAME.price end
function HasSendMailItem(k) return GAME.items[k] ~= nil end
function GetSendMailItem(k) local it = GAME.items[k]; if it then return it.name, it.id, 134, it.n, 1 end end
function CursorHasItem() return GAME.cursor ~= nil end
function ClearCursor() GAME.cursor = nil end
function ClickSendMailItemButton(k, clear)
  if clear then GAME.items[k] = nil; return end
  if not GAME.cursor then return end
  for i = 1, 12 do if not GAME.items[i] then GAME.items[i] = GAME.cursor; GAME.cursor = nil; return end end
end
function SetSendMailMoney(c) GAME.sendMoney = c end
function SetSendMailCOD(c) GAME.cod = c end
function SendMail(to, subject, body) GAME.sent = { to = to, subject = subject, body = body, money = GAME.sendMoney, cod = GAME.cod } end
function GetInboxNumItems() return 0 end
function GetActiveLootRollIDs() return {} end
function GetTime() return GAME.t or 1000 end
function InCombatLockdown() return false end
C_Container = {
  GetContainerNumSlots = function(bag) return bag == 0 and 4 or 0 end,
  GetContainerItemInfo = function(bag, s) local it = GAME.bags[s]; if bag == 0 and it then
      return { itemID = it.id, iconFileID = 133, stackCount = it.n, quality = 1, hyperlink = "|cff|Hitem:" .. it.id .. "|h[" .. it.name .. "]|h|r", isBound = it.bound } end end,
  PickupContainerItem = function(bag, s)
    if GAME.cursor then GAME.bags[s] = GAME.cursor; GAME.cursor = nil else GAME.cursor = GAME.bags[s]; GAME.bags[s] = nil end end,
}
C_Timer = { After = function() end, NewTicker = function() return { Cancel = function() end } end, NewTimer = function() return { Cancel = function() end } end }
time = os.time
WowCompanion = {
  S = function(fn) local ok, v = pcall(fn); if ok then return v end end,
  SN = function(fn) local ok, v = pcall(fn); if ok and type(v) == "number" then return v end end,
  isPlain = function(v) return true end,
  CALL = function(fn) return pcall(fn) end,
  send = function(t) table.insert(SENT, t) end, encode = function(t) return t end,
  push = function() end, pushThrottled = function() end, emit = function() end, snapshot = function() end,
  ABSENT = {}, SECRET = {}, THREW = {},
  pad = { op = function(name, ns, h) ROWS[name] = h; return {} end,
          slotFor = function(ns, key) if not CELLS[ns .. ":" .. key] then cellN = cellN + 1; CELLS[ns .. ":" .. key] = cellN end; return CELLS[ns .. ":" .. key] end,
          retain = function() end, describe = function() return {} end },
}
setmetatable(_G, { __index = function(_, k) return dummy end })
-- the app's ChatStream, in short: a character's cell (ChatStream.cellOf)
function TYPE(s)
  for k = 1, #s do
    local c = s:byte(k)
    if c >= 32 and c <= 47 then ROWS.chq(nil, c - 8) elseif c <= 87 then ROWS.chr(nil, c - 48) else ROWS.chs(nil, c - 88) end
  end
end
function LASTT() for i = #SENT, 1, -1 do if SENT[i].type == "chatt" then return SENT[i] end end end
'''
chat = open(os.path.join(addons, "WowCompanion", "Chat.lua"), encoding="utf-8").read()
src = open(os.path.join(addons, "WowCompanionInteract", "Interact.lua"), encoding="utf-8").read()
src = src.replace("local ADDON = ...", "local ADDON = 'x'", 1)
src += "\n_G.__N = N; _G.__ACT = ACT; _G.__ON = ON; _G.__MAIL = MAIL; _G.__AH = AH; _G.__build = buildDoc\n"
names = sys.argv[1:] or sorted(n for n in os.listdir(HERE) if n.startswith("t_") and n.endswith(".lua"))
fails = 0
for n in names:
    L = lupa.LuaRuntime()                    # each test file in a fresh game: no state leaks from one to the next
    L.execute(STUB)
    L.execute(chat.replace("local ADDON = ...", "local ADDON = 'WowCompanion'", 1))
    L.execute(src)
    res = L.execute(open(os.path.join(HERE, n), encoding="utf-8").read())
    print(res)
    fails += str(res).count("FAIL ")
print("failed: %d" % fails)
sys.exit(1 if fails else 0)
