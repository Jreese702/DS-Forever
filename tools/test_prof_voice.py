"""Professions p24 (voice1, the user 2026-11-16: voice to text in ALL search boxes, "A opens the keyboard, X opens our own voice prompt"; the
pick: "Professions search (which gets controller A and X)"): while the app's keyboard (or its voice page) types the room's search, B is the
ROOM's, so it closes the keyboard and never the game's window. Loads the real WowCompanionProfessions/Professions.lua in Lua 5.1 with
stand-in game functions and drives its control row (ctl page / kbd / nokbd / free):
  - the page's take: B is the game's close button (shown) or passed to the game (hidden) - as before;
  - ctl kbd: the re-take has NO back button and passes nothing; Home is told B goes "back"; the prof doc says kbd = true;
  - ctl nokbd: B the game's close again ("home"); free forgets the keyboard;
  - in a fight ctl kbd is refused ("combat": bindings cannot change) and the capture's red flash is asked; nothing is re-taken;
  - the two new columns come after every old one (the old keys keep their columns); no trace (no SetBinding / macro / CVar / window).
Run: python -B tools/test_prof_voice.py
"""
import os
import sys
from lupa import lua51

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = open(os.path.join(ROOT, 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons', 'WowCompanionProfessions',
                        'Professions.lua'), encoding='utf-8').read()
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
TIMERS = {}
C_Timer = { NewTicker = function() return stub end, NewTimer = function() return stub end,
            After = function(s, fn) TIMERS[#TIMERS + 1] = { at = NOW + s, fn = fn } end }
function run(dt)
    local upto = NOW + (dt or 0)
    for _ = 1, 10000 do
        local bi, best = nil, nil
        for i, t in ipairs(TIMERS) do if t.at <= upto and (not best or t.at < best.at) then bi, best = i, t end end
        if not bi then break end
        table.remove(TIMERS, bi)
        if best.at > NOW then NOW = best.at end
        pcall(best.fn)
    end
    NOW = upto
end
HANDLERS = {}
function CreateFrame(kind, name)
    local fr = setmetatable({ attrs = {}, name = name, ev = {} }, { __index = function() return stub end })
    function fr:SetScript(k, fn) if k == "OnEvent" then HANDLERS[#HANDLERS + 1] = { fr = fr, fn = fn } end end
    function fr:RegisterEvent(e) self.ev[e] = true; return true end
    function fr:SetAttribute(k, v) self.attrs[k] = v end
    function fr:GetName() return self.name end
    if name then _G[name] = fr end
    return fr
end
function fire(ev, ...) for _, h in ipairs(HANDLERS) do if h.fr.ev[ev] then pcall(h.fn, h.fr, ev, ...) end end end
COMBAT = false
function InCombatLockdown() return COMBAT end
function UnitAffectingCombat() return COMBAT end
function UnitName() return "Shamy" end
function GetRealmName() return "Forever" end
TRACE = {}
for _, n in ipairs({ "SetBinding", "SaveBindings", "DeleteMacro", "CreateMacro", "EditMacro", "SetCVar", "ShowUIPanel", "HideUIPanel",
                     "ToggleProfessionsBook", "CloseTradeSkill", "SetOverrideBinding", "SetOverrideBindingClick" }) do
    _G[n] = function() TRACE[#TRACE + 1] = n end
end
CLOSEVIS = true
ProfessionsFrame = { IsShown = function() return true end, IsVisible = function() return true end }
ProfessionsFrameCloseButton = { IsVisible = function() return CLOSEVIS end }
ProfessionMicroButton = { IsVisible = function() return true end }
SENT, SPECS, ROWS, SLOTS = {}, {}, {}, {}
local nslot = {}
WowCompanion = {
    S = function(fn) local ok, v = pcall(fn); if ok then return v end end,
    SN = function(fn) local ok, v = pcall(fn); if ok and type(v) == "number" then return v end end,
    CALL = function(fn, ...) return pcall(fn, ...) end,
    send = function(t) SENT[#SENT + 1] = t end, encode = function(t) return t end, push = function() end,
    isPlain = function() return true end, ABSENT = {}, SECRET = {}, THREW = {},
    pad = { op = function(name, ns, h) ROWS[name] = h; return {} end, secureRow = function() return {} end,
            slotFor = function(ns, key)
                SLOTS[ns] = SLOTS[ns] or {}
                if SLOTS[ns][key] == nil then nslot[ns] = (nslot[ns] or -1) + 1; SLOTS[ns][key] = nslot[ns] end
                return SLOTS[ns][key]
            end,
            describe = function() return { rows = { prof = 37 } } end, onLayout = function() end, retain = function() end },
    capture = { version = 17,
                take = function(g, spec) SPECS[#SPECS + 1] = spec; return true end,
                release = function() return true end,
                state = function() return { holder = "professions" } end,
                bnext = function(g, w) BNEXT = w end,
                refused = function() REFUSED = (REFUSED or 0) + 1; return true end,
                pass = function() return true end },
}
function lastSpec() return SPECS[#SPECS] end
function lastAck(op) for i = #SENT, 1, -1 do local t = SENT[i]; if t.type == "profack" and t.op == op then return t end end end
function lastDoc() for i = #SENT, 1, -1 do local t = SENT[i]; if t.type == "prof" then return t.p end end end
function passHas(spec, k) if not spec.pass then return false end; for _, x in ipairs(spec.pass) do if x == k then return true end end; return false end
function extraHas(spec, k) if not spec.extra then return false end; for _, x in ipairs(spec.extra) do if x == k then return true end end; return false end
''')
load = L.eval('function(src) local f, e = loadstring(src, "@Professions.lua"); if not f then error(e) end; return f end')
ok_load = True
try:
    load(SRC)('WowCompanionProfessions')
except Exception as e:  # noqa: BLE001
    ok_load = False; print(e)
check(ok_load, "Professions.lua loads (stand-in game) with no error")
check(L.eval('ROWS.prof ~= nil'), "its control row registered")
L.execute('fire("PLAYER_LOGIN"); fire("TRADE_SKILL_SHOW"); run(1)')

# the page's take, as before: B the game's close button (shown)
L.execute('ROWS.prof("page"); run(1)')
check(L.eval('lastSpec() ~= nil and lastSpec().back') == "ProfessionsFrameCloseButton", "page: B is the game's close button",
      L.eval('lastSpec() and lastSpec().back'))
check(L.eval('BNEXT') == "home", "page: Home is told B goes home", L.eval('BNEXT'))

# the app's keyboard up
L.execute('N0 = #SPECS; ROWS.prof("kbd"); run(1)')
check(L.eval('#SPECS') > L.eval('N0'), "kbd: the room takes the controller again (its spec replaced)")
check(L.eval('lastSpec().back') is None and not L.eval('passHas(lastSpec(), "PAD2")'),
      "kbd: no back button, nothing passed: B is the room's (it closes the keyboard)")
check(L.eval('BNEXT') == "back", "kbd: Home is told B steps back (not Home)", L.eval('BNEXT'))
check(L.eval('lastAck("kbd") and lastAck("kbd").ok') is True, "kbd answered ok")
check(L.eval('lastDoc() and lastDoc().kbd') is True, "the prof doc says kbd", L.eval('lastDoc() and lastDoc().kbd'))
# p25: Start held from the game while the keyboard is up (its Search key; the shoulders stay held as before)
check(L.eval('extraHas(lastSpec(), "PADFORWARD")') is True, "kbd: Start held from the game (the keyboard's Search key)")
check(L.eval('extraHas(lastSpec(), "PADLSHOULDER") and extraHas(lastSpec(), "PADRSHOULDER")') is True, "kbd: LB / RB still held")
# a craft starting meanwhile changes nothing of B while typing
L.execute('ROWS.prof("page"); run(1)')
check(L.eval('lastSpec().back') is None, "a page re-take while the keyboard is up keeps B the room's")
check(L.eval('extraHas(lastSpec(), "PADFORWARD")') is True, "a page re-take while the keyboard is up keeps Start held")

# the keyboard down
L.execute('ROWS.prof("nokbd"); run(1)')
check(L.eval('lastSpec().back') == "ProfessionsFrameCloseButton", "nokbd: B the game's close again", L.eval('lastSpec().back'))
check(L.eval('BNEXT') == "home", "nokbd: Home is told B goes home again")
check(L.eval('extraHas(lastSpec(), "PADFORWARD")') is False, "nokbd: Start the game's / Home's again (not held)")
check(L.eval('lastDoc() and lastDoc().kbd') is None, "the prof doc no longer says kbd")
L.execute('N1 = #SPECS; ROWS.prof("nokbd"); run(1)')
check(L.eval('#SPECS') == L.eval('N1'), "a second nokbd re-takes nothing")

# the close button hidden by the game: B passed to the game (p21), and the keyboard takes that back too
L.execute('CLOSEVIS = false; ROWS.prof("page"); run(1)')
check(L.eval('passHas(lastSpec(), "PAD2")'), "page with the close button hidden: B passed to the game (as before)")
L.execute('ROWS.prof("kbd"); run(1)')
check(not L.eval('passHas(lastSpec(), "PAD2")') and L.eval('lastSpec().back') is None, "kbd: B no longer passed to the game")
L.execute('ROWS.prof("nokbd"); run(1)')
check(L.eval('passHas(lastSpec(), "PAD2")'), "nokbd: passed to the game again")
L.execute('CLOSEVIS = true')

# in a fight: refused, the red flash asked, nothing re-taken
L.execute('COMBAT = true; N2 = #SPECS; REFUSED = 0; ROWS.prof("kbd"); run(1)')
check(L.eval('lastAck("kbd").ok') is False and L.eval('lastAck("kbd").why') == "combat", "kbd in a fight: refused (combat)",
      L.eval('lastAck("kbd").why'))
check(L.eval('REFUSED') == 1, "the capture's red flash asked")
check(L.eval('#SPECS') == L.eval('N2'), "nothing re-taken in the fight")
L.execute('COMBAT = false')

# free forgets the keyboard
L.execute('ROWS.prof("page"); ROWS.prof("kbd"); ROWS.prof("free"); ROWS.prof("page"); run(1)')
check(L.eval('lastSpec().back') == "ProfessionsFrameCloseButton", "free then page: B the game's close (the keyboard forgotten)")

# the columns: the two new keys after every old one
cols = {k: L.eval('SLOTS.profctl["%s"]' % k) for k in ("make:1", "forget:2", "kbd", "nokbd")}
check(cols["kbd"] is not None and cols["nokbd"] is not None and cols["kbd"] > cols["forget:2"] and cols["nokbd"] == cols["kbd"] + 1,
      "kbd / nokbd appended after the old keys (forget:2 keeps its column)", cols)
check(cols["nokbd"] < 40, "still inside the control row's 40 columns", cols["nokbd"])
check(L.eval('#TRACE') == 0, "no trace: no binding, macro, CVar or window call", L.eval('#TRACE'))
check('p24 (voice1, the user 2026-11-16' in SRC and '"A opens the keyboard, X opens our own voice prompt"' in SRC,
      "the version note quotes the user")
check("local PV = \"p27\"" in SRC and "p25 (voice1 review, the user 2026-11-16" in SRC, "p25: the tag bumped (now p27), its note quotes the user")

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
