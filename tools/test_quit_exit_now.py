"""The game's log out / exit countdowns on the bottom screen (WowCompanionInteract/Dialogs.lua d32; build 1462, the user: "when exiting
the game and the timer pops up it doesnt have the 'exit now' option that it normally should"), off-device: Dialogs.lua alone under the
stub game of test_quest_start.py.

    python tools/test_quit_exit_now.py

Checks, against this client's own definitions (Blizzard_StaticPopup_Game gamedialogdefs.lua: QUIT = button1 QUIT_NOW "Exit Now" whose
OnAccept is ForceQuit, button2 CANCEL; CAMP = button2 CANCEL only): the QUIT countdown is mirrored WITH "Exit Now" and its own touch
cell, that cell is a secure click aimed at the game's own button 1 (nothing of ours calls ForceQuit), A is bound to nothing while QUIT
shows and "Exit Now" carries no key (d22: never pressed by the controller), B is Cancel; the CAMP countdown shows Cancel alone; no
file of the add-ons calls ForceQuit / Quit / Logout itself. Needs lupa. Exit code 1 on any failure."""
import os
import re
import sys

import lupa

from test_quest_start import STUB, SRC, ROOT

POPUPS = r'''
-- a StaticPopup as the game builds it: Text, GetButton(k), the gamepad glyphs by key (gamepadIcons: the icon's parent = the button)
function POPUP(i, which, labels, left)
    local f = CreateFrame("Frame", "StaticPopup" .. i)
    f.which, f.timeleft, f.shown = which, left, true
    f.Text = { GetText = function() return which == "QUIT" and "20 Seconds until exit" or "20 Seconds until logout" end }
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
function DOC()
    WowCompanion.dialogs.alive(); FLUSH()
    for i = #SENT, 1, -1 do if SENT[i].type == "dialogs" then return SENT[i] end end
end
function FIND(doc, which) for _, d in ipairs(doc.list) do if d.which == which then return d end end end
function BTN(d, k) for _, b in ipairs(d.buttons) do if b.k == k then return b end end end
'''

TEST = r'''
local out = {}
local function chk(n, c) out[#out + 1] = (c and "PASS " or "FAIL ") .. n end
FIRE("PLAYER_LOGIN")                                   -- the touch row (offerTouch, after a second)
FIRE("PLAYER_ENTERING_WORLD", true, false)
local q = POPUP(1, "QUIT", { "Exit Now", "Cancel" }, 18)
local doc = DOC()
local d = doc and FIND(doc, "QUIT")
chk("QUIT is mirrored", d ~= nil and d.top == nil)
local x, c = d and BTN(d, 1), d and BTN(d, 2)
chk("QUIT: 'Exit Now' is offered (the game's button 1)", x ~= nil and x.label == "Exit Now" and x.on == true)
chk("QUIT: Cancel too, after it (the game's order)", c ~= nil and c.label == "Cancel" and d.buttons[1].k == 1 and d.buttons[2].k == 2)
chk("QUIT: the countdown's seconds go with it", d and d.left == 18)
chk("QUIT: 'Exit Now' has its own touch cell (dialog 1, button 1 = column 0)", d and d.cells and d.cells.b1 == 0 and d.cells.b2 == 1)
local cell = ROW and ROW.cells[0]
chk("the cell is a secure click on the GAME's own 'Exit Now' button (the game's OnAccept runs, nothing of ours)",
    cell ~= nil and cell.attr.type == "click" and cell.attr.clickbutton == q.buttons[1])
chk("the Cancel cell clicks the game's Cancel", ROW and ROW.cells[1].attr.clickbutton == q.buttons[2])
chk("QUIT is guarded (the controller never presses 'Exit Now')", d and d.guard == true)
chk("the dialogs hold the controller on QUIT with A bound to NOTHING", CAP.holder == "dialogs" and CAP.spec and CAP.spec.a == nil)
chk("B is Cancel (a binding click of our B, aimed at the game's Cancel)",
    CAP.spec and CAP.spec.back == "WowCompanionDlgB" and _G.WowCompanionDlgB.attr.clickbutton == q.buttons[2])
chk("'Exit Now' carries no key (no A glyph drawn on it); Cancel is B", x and x.key == nil and c and c.key == "PAD2")

-- the log out countdown: the game's CAMP has no button 1, only Cancel
q.shown = false
local camp = POPUP(1, "CAMP", { nil, "Cancel" }, 20)
doc = DOC()
local cd = doc and FIND(doc, "CAMP")
chk("CAMP is mirrored", cd ~= nil and FIND(doc, "QUIT") == nil)
chk("CAMP: Cancel alone (as the game: no 'Exit Now' on a log out)", cd and #cd.buttons == 1 and cd.buttons[1].k == 2 and cd.buttons[1].label == "Cancel")
chk("CAMP: not guarded; B is Cancel", cd and cd.guard == nil)
return table.concat(out, "\n")
'''


def run(test):
    L = lupa.LuaRuntime()
    L.execute(STUB)
    L.execute(POPUPS)
    L.execute(open(SRC, encoding='utf-8').read())
    return str(L.execute(test))


def static_checks():
    """no add-on file calls the game's exits itself: Exit / Log Out are the game's own slash commands on secure cells (/quit, /logout),
    Exit Now is the game's own button; CancelLogout is the one plain call (Radial's gm_cancel)"""
    out = []
    addons = os.path.join(ROOT, 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons')
    bad = []
    for dp, _, fs in os.walk(addons):
        for fn in fs:
            if not fn.endswith('.lua'):
                continue
            for n, line in enumerate(open(os.path.join(dp, fn), encoding='utf-8', errors='replace'), 1):
                code = line.split('--', 1)[0]
                if re.search(r'(?<![\w.:])(ForceQuit|Quit|Logout)\s*\(', code):
                    bad.append('%s:%d' % (fn, n))
    out.append(('PASS ' if not bad else 'FAIL ') + 'no add-on code calls ForceQuit / Quit / Logout itself' + (' (%s)' % ', '.join(bad) if bad else ''))
    radial = open(os.path.join(addons, 'WowCompanionSpells', 'Radial.lua'), encoding='utf-8').read()
    m = re.search(r'local CELLS = \{ \[0\] = \{ "gm_logout", "(/[a-z]+)" \}, \[1\] = \{ "gm_quit", "(/[a-z]+)" \}', radial)
    ok = m is not None and m.group(1) == '/logout' and m.group(2) == '/quit'
    out.append(('PASS ' if ok else 'FAIL ') + 'the Log Out cell runs /logout (CAMP) and the Exit Game cell /quit (QUIT), per their labels')
    labels = re.search(r'add\("logout", "LOG_OUT".*?COL\.gm_logout\)\s*\n\s*add\("quit", "EXIT_GAME".*?COL\.gm_quit\)', radial)
    out.append(('PASS ' if labels else 'FAIL ') + 'the menu items: LOG_OUT on gm_logout, EXIT_GAME on gm_quit')
    return "\n".join(out)


def main():
    res = run(TEST) + "\n" + static_checks()
    print(res)
    fails = res.count("FAIL ")
    print("failed: %d" % fails)
    return 1 if fails else 0


if __name__ == '__main__':
    sys.exit(main())
