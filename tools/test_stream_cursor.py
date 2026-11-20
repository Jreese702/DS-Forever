"""fix/stream-cursor (the user, 2026-11-19, streaming 1.0.36: "the cursor keeps randomly showing on the game"), off-device.

A. TapPad pad25 under tools/wowstub.py (the REAL TapPad.lua with the world files): a press of ours ARMS the blank cursor at once - the move
   to the cell and the game's park after it never show it (pad13 showed it on that move until it had rested a second); an item on the
   cursor shows it; a move nothing of ours explains is a real mouse (shown, pad.humanAt); a move a frame BEFORE its press is still ours;
   under the hardware cursor (gxCursor not "0") nothing is drawn blank, as pad14.
B. Dialogs d35 under test_quest_start.py's stub: the stream's ONE `stay` keeps a dialog off the top screen past LIFE with no further
   sign; a real mouse (pad.humanAt) brings it back; a new dialog in that frame needs its own sign; plain `alive` still lapses after LIFE.
C. Interact i167s (i169 since the 1501 merge): the iact "stay" cell (asked for last), ACT.stay, no answer of its own.
Needs lupa. Exit code 1 on any failure."""
import os
import sys

import lupa

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import ADDONS, boot_world      # noqa: E402
from test_quest_start import STUB, SRC as DIALOGS_SRC   # noqa: E402

FAILS = []


def check(ok, what):
    print(("PASS " if ok else "FAIL ") + what)
    if not ok:
        FAILS.append(what)


# ---------------------------------------------------------------------------------------------------------------- A. TapPad pad25
TAPPAD = open(os.path.join(ADDONS, "WowCompanion", "TapPad.lua"), encoding="utf-8").read()
check(int(__import__('re').search(r'local PADV = "pad(\d+)"', TAPPAD).group(1)) >= 25 and "pad25 NO CURSOR FROM OUR OWN PRESSES" in TAPPAD and "the cursor keeps randomly showing on the game" in TAPPAD, "A. TapPad pad25, its note quotes the user")

PRE = r'''
CUR = { x = 960, y = 640 }
CURSTATE = "game"
GX = "0"
CURINFO = false                    -- never nil: the stub's _G answers an unset global with its STUB table
function GetCursorPosition() return CUR.x, CUR.y end
function SetCursor(p) CURSTATE = "blank"; CURPIC = p end
function ResetCursor() CURSTATE = "game" end
function GetCursorInfo() return CURINFO or nil end
function SpellIsTargeting() return false end
C_CVar = { GetCVar = function(k) if k == "gxCursor" then return GX end end, SetCVar = function() return true end }
'''
L, errs = boot_world(pre=PRE, strict=False)
check(not errs, "A. the world loads (%s)" % "; ".join(errs)[:200])
check(L.eval('type(WowCompanion.pad.cursorLook) == "function"'), "A. the cursor's look is there")


def look(dt=0.2):
    L.execute('NOW = NOW + %f; WowCompanion.pad.cursorLook(nil, %f)' % (dt, dt))


def at(x, y):
    L.execute('CUR.x, CUR.y = %f, %f' % (x, y))


def press(row="ui", col=34):
    return L.eval('''(function()
        local r = WowCompanion.pad.rows["%s"]; local b = r and r.cells and r.cells[%d]
        if not b then return "no cell" end
        local fn = b:GetScript("OnClick"); if not fn then return "no script" end
        fn(b, "LeftButton", true); return "ok" end)()''' % (row, col))


state = lambda: L.eval('CURSTATE')
armed = lambda: L.eval('WowCompanion.pad.cursorArmed()')
human = lambda: L.eval('WowCompanion.pad.humanAt')

PARK = (960, 640)
CELL = (12, 1070)
at(*PARK)
for _ in range(8):
    look()
check(state() == "blank", "A. pad13 kept: resting over the world for a second = blank")
check(not armed(), "A. nothing armed before a press of ours")

# a press posted by Desktop: the move to the cell and the press in one frame, the park after it
at(*CELL)
check(press() == "ok", "A. a press on ui:34")
look(0.016)
check(state() == "blank" and armed(), "A. the move to the cell with its press: still blank (pad13 showed it here)")
at(*PARK)
look(0.3)
check(state() == "blank", "A. the game's park right after: still blank")
for _ in range(15):
    look(0.2)
check(state() == "blank" and armed() and human() is None, "A. resting at the park 3 s later: blank, armed, no real mouse seen")

# an item on the cursor shows it, and it goes blank again once the cursor is free
L.execute('CURINFO = "item"')
look()
check(state() == "game", "A. an item on the cursor: the game's own cursor")
L.execute('CURINFO = false')
look()
check(state() == "blank", "A. the item gone: blank again (still armed)")

# a move back by 2 px to the park spot it learned: still ours
at(PARK[0] + 2, PARK[1])
look()
look()
check(state() == "blank" and armed(), "A. a move back to the park spot it learned: ours, still blank")

# a move one frame BEFORE its press (the posted move reached the game first): ours
at(30, 1060)
look(0.016)
check(state() == "blank", "A. a move with no press yet: blank while it waits")
press("uisw", 3)
look(0.016)
look(0.2)
check(state() == "blank" and armed() and human() is None, "A. its press a frame later: the move was ours (no real mouse)")

# a real mouse: a move no press explains, well after the last press
for _ in range(10):
    look(0.2)
at(400, 300)
look(0.05)
check(state() == "blank", "A. a real move: blank for at most HUMAN_S while a press might follow")
look(0.2)
check(state() == "game" and not armed() and isinstance(human(), (int, float)), "A. a real mouse: the game's own cursor, disarmed, humanAt said")

# a press again arms it at once
at(*CELL)
press("ui", 30)
look(0.016)
check(state() == "blank" and armed(), "A. the next press of ours: blank at once again")

# under the hardware cursor nothing goes blank (pad14: it drew a black box on the Thor)
L.execute('GX = "1"')
for _ in range(3):
    look()
check(state() == "game", "A. hardware cursor (gxCursor 1): the game's own cursor, never the blank")
L.execute('GX = "0"')
check(L.eval('#SPY') == 0 or all(L.eval('SPY[%d]' % i) != "SetCVar" for i in range(1, int(L.eval('#SPY')) + 1)),
      "A. no global SetCVar call")

# ---------------------------------------------------------------------------------------------------------------- B. Dialogs d35
DIALOGS = open(DIALOGS_SRC, encoding="utf-8").read()
check('local DV = "d35"' in DIALOGS and "the cursor keeps randomly showing on the game" in DIALOGS, "B. Dialogs d35, its note quotes the user")
D = lupa.LuaRuntime()
D.execute(STUB)
D.execute(r'''
function POPUP(i, which, labels)
    local f = CreateFrame("Frame", "StaticPopup" .. i)
    f.which, f.shown, f.alpha = which, true, 1
    f.SetAlpha = function(self, a) self.alpha = a end
    f.Text = { GetText = function() return which end }
    f.buttons, f.gamepadIcons = {}, {}
    for k = 1, 4 do
        local b = CreateFrame("Button", "StaticPopup" .. i .. "Button" .. k)
        b.shown = labels[k] ~= nil
        b.GetText = function() return labels[k] end
        f.buttons[k] = b
        if labels[k] then f.gamepadIcons["PAD" .. k] = { GetParent = function() return b end } end
    end
    f.GetButton = function(self, k) return self.buttons[k] end
    f.gamepadFocused = true
    return f
end
function LAST() for i = #SENT, 1, -1 do if SENT[i].type == "dialogs" then return SENT[i] end end end
''')
D.execute(DIALOGS)
D.execute('FIRE("PLAYER_LOGIN"); FIRE("PLAYER_ENTERING_WORLD", true, false)')
D.execute('P1 = POPUP(1, "DEATH", { "Release Spirit" }); FLUSH()')
D.execute('WowCompanion.dialogs.stay(); FLUSH()')
check(D.eval('P1.alpha') == 0, "B. the stream's stay: off the top screen")
check(D.eval('LAST() and LAST().life and LAST().life.stay') == 0, "B. the document names the stay cell (doc.life.stay)")
D.execute('GAME.t = GAME.t + 30; FLUSH()')
check(D.eval('P1.alpha') == 0, "B. 30 s with no sign after the ONE stay: still off the top screen")
D.execute('WowCompanion.pad.humanAt = GAME.t + 1; GAME.t = GAME.t + 2; FLUSH()')
check(D.eval('P1.alpha') == 1, "B. a real mouse moved (pad.humanAt): back on the top screen")
D.execute('WowCompanion.pad.humanAt = nil; GAME.t = GAME.t + 30; FLUSH()')
check(D.eval('P1.alpha') == 1, "B. and it stays the game's until the app says so again")
# another dialog in that frame needs its own sign
D.execute('WowCompanion.dialogs.stay(); FLUSH()')
check(D.eval('P1.alpha') == 0, "B. a stay again: off the top screen")
D.execute('P1.which = "RESURRECT"; P1.Text = { GetText = function() return "RESURRECT" end }; GAME.t = GAME.t + 30; FLUSH(); GAME.t = GAME.t + 2; FLUSH()')
check(D.eval('P1.alpha') == 1, "B. another dialog in that frame, no sign for it: back on top after the grace")
# Local: alive lapses after LIFE as before
D.execute('P1.shown = false; FLUSH(); P2 = POPUP(2, "PARTY_INVITE", { "Accept", "Decline" }); WowCompanion.dialogs.alive(); FLUSH()')
check(D.eval('P2.alpha') == 0, "B. alive (Local): off the top screen")
D.execute('GAME.t = GAME.t + 9; FLUSH()')
check(D.eval('P2.alpha') == 1, "B. alive (Local): back on top once LIFE has passed with no sign, unchanged")

# ---------------------------------------------------------------------------------------------------------------- C. Interact i167
INTERACT = open(os.path.join(ADDONS, "WowCompanionInteract", "Interact.lua"), encoding="utf-8").read()
_ivm = __import__('re').search(r'local IV = "i(\d+)"', INTERACT)   # 1502: i170 (pet trainer) on top of i169
check(_ivm is not None and int(_ivm.group(1)) >= 169 and "i167s (fix/stream-cursor" in INTERACT, "C. Interact i169 or later (i167s, the stay cell, kept in the 1501 merge)")
check('slot("iact", "stay")\n    local ok, d = pcall(pad.describe)' in INTERACT, "C. the stay cell is asked for last (no column above moves)")
check("function ACT.stay()" in INTERACT and 'w[1] == "stay"' in INTERACT, "C. ACT.stay, no answer of its own")

print("failed: %d" % len(FAILS))
sys.exit(1 if FAILS else 0)
