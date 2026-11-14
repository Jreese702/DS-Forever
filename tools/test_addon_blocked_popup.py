"""OUR ADD-ON'S "HAS BEEN BLOCKED" POP-UP IS NEVER ANSWERED WITH DISABLE (WowCompanionInteract/Dialogs.lua d33; issue #278, the user's
pick "Yes, protect", 2026-11-13), off-device: Dialogs.lua alone under the stub game of test_quest_start.py (test_quit_exit_now.py's way).

    python tools/test_addon_blocked_popup.py

The game's definitions (Blizzard_StaticPopup_Game gamedialogdefs.lua): ADDON_ACTION_FORBIDDEN = button1 DISABLE (its OnAccept:
C_AddOns.DisableAddOn(data); ReloadUI()), button2 IGNORE_DIALOG. Issue #278's player came up with WowCompanionSpells DISABLED: A on
that pop-up is the game's button 1. Checks: for a DS Forever add-on (data / text WowCompanion*) the pop-up is guarded with Ignore as its
safe answer (safe 2, never 1); the dialogs hold the controller with A AND B as binding clicks aimed at the game's own Ignore button -
also while the app draws no dialogs (D.mode off); the mirror's A glyph is on Ignore, none on Disable; ADDON_ACTION_BLOCKED alike; another
add-on's pop-up is left exactly as the game has it; no add-on file calls DisableAddOn, or the pop-up's own functions. Needs lupa."""
import os
import re
import sys

import lupa

from test_quest_start import STUB, SRC, ROOT
from test_quit_exit_now import POPUPS

TEST = r'''
local out = {}
local function chk(n, c) out[#out + 1] = (c and "PASS " or "FAIL ") .. n end
FIRE("PLAYER_LOGIN")
FIRE("PLAYER_ENTERING_WORLD", true, false)
local function blocked(which, name)
    local f = POPUP(1, which, { "Disable", "Ignore" }, nil)
    f.data = name
    f.Text = { GetText = function() return name .. " has been blocked from an action only available to the Blizzard UI. You can disable this addon and reload the UI." end }
    return f
end
for _, which in ipairs({ "ADDON_ACTION_FORBIDDEN", "ADDON_ACTION_BLOCKED" }) do
    local f = blocked(which, "WowCompanionSpells")
    local doc = DOC()
    local d = doc and FIND(doc, which)
    chk(which .. " for WowCompanionSpells: mirrored, guarded, safe = Ignore (2), never = Disable (1)",
        d ~= nil and d.guard == true and d.safe == 2 and d.never == 1)
    chk(which .. ": the dialogs hold the controller, A AND B = binding clicks on our B, aimed at the game's own Ignore",
        CAP.holder == "dialogs" and CAP.spec and CAP.spec.a == "WowCompanionDlgB" and CAP.spec.back == "WowCompanionDlgB"
        and _G.WowCompanionDlgB.attr.clickbutton == f.buttons[2])
    local dis, ign = d and BTN(d, 1), d and BTN(d, 2)
    chk(which .. ": Disable carries no key; A's glyph is on Ignore", dis ~= nil and dis.key == nil and ign ~= nil and ign.key == "PAD1")
    f.shown = false
    DOC()
end
-- also while the app draws no dialogs (the game's own A would be Disable)
WowCompanion.dialogs.setMode(false)
local f = blocked("ADDON_ACTION_FORBIDDEN", "WowCompanionHub")
local doc = DOC()
chk("D.mode off: still guarded, the controller still held with A on Ignore",
    CAP.holder == "dialogs" and CAP.spec and CAP.spec.a == "WowCompanionDlgB")
f.shown = false
DOC()
WowCompanion.dialogs.setMode(true)
-- another add-on's pop-up: the game's own, untouched
local o = blocked("ADDON_ACTION_FORBIDDEN", "SomeoneElsesAddon")
doc = DOC()
local od = doc and FIND(doc, "ADDON_ACTION_FORBIDDEN")
chk("another add-on's pop-up: not guarded, no safe / never", od ~= nil and od.guard == nil and od.safe == nil and od.never == nil)
o.shown = false
return table.concat(out, "\n")
'''


def run(test):
    L = lupa.LuaRuntime()
    L.execute(STUB)
    L.execute(POPUPS)
    L.execute(open(SRC, encoding='utf-8').read())
    return str(L.execute(test))


def static_checks():
    out = []
    addons = os.path.join(ROOT, 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons')
    bad = []
    for dp, _, fs in os.walk(addons):
        for fn in fs:
            if not fn.endswith('.lua'):
                continue
            for n, line in enumerate(open(os.path.join(dp, fn), encoding='utf-8', errors='replace'), 1):
                code = line.split('--', 1)[0]
                if re.search(r'DisableAddOn\s*\(|StaticPopup_(Hide|Show|OnClick)\s*\(|StaticPopupDialogs\s*\[', code):
                    bad.append('%s:%d' % (fn, n))
    out.append(('PASS ' if not bad else 'FAIL ') + 'no add-on code calls DisableAddOn / StaticPopup_Hide / _Show / _OnClick or writes StaticPopupDialogs'
               + (' (%s)' % ', '.join(bad) if bad else ''))
    src = open(SRC, encoding='utf-8').read()
    out.append(('PASS ' if 'local DV = "d33"' in src else 'FAIL ') + 'Dialogs says d33')
    return "\n".join(out)


def main():
    res = run(TEST) + "\n" + static_checks()
    print(res)
    fails = res.count("FAIL ")
    print("failed: %d" % fails)
    return 1 if fails else 0


if __name__ == '__main__':
    sys.exit(main())
