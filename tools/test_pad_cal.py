"""pad24 THE STREAM'S TAPS MEASURED (the user, 2026-11-18, test 1494 streamed: "testing stream, ui still didnt hide"), off-device: the REAL
TapPad.lua and Hub.lua (the world files) under tools/wowstub.py.
- describe() publishes `calib`: the calibration strip's box (CAL_GAP left of the grid, the band's placed height), its press count and
  where the game saw its last presses ("x,y" game px, top-left, newest last, at most 4);
- a press on the strip runs nothing else (no pad row's handler, no ring entry) and the Thor's double press counts once;
- every cell press also keeps where the game saw it (rxDoc `at`, beside `last`);
- the catcher's band zone takes the strip in;
- the row budget is unchanged (the strip is no row).
Needs lupa. Exit code 1 on any failure."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import ADDONS, boot_world      # noqa: E402

FAILS = []


def check(ok, what):
    print(("PASS " if ok else "FAIL ") + what)
    if not ok:
        FAILS.append(what)


L, errs = boot_world(strict=False)
check(not errs, "the world loads (%s)" % "; ".join(errs)[:200])
TAPPAD = open(os.path.join(ADDONS, "WowCompanion", "TapPad.lua"), encoding="utf-8").read()
check(('local PADV = "pad24"' in TAPPAD or (int(__import__('re').search(r'local PADV = "pad(\d+)"', TAPPAD).group(1)) >= 25 and "pad24 THE STREAM'S TAPS MEASURED" in TAPPAD)) and "testing stream, ui still didnt hide" in TAPPAD, "a. TapPad pad24, its note quotes the user")

# the game's cursor, in screen units (bottom-left origin); UIParent's effective scale = the stub screen's px per UI unit
L.execute('''CUR = { x = 0, y = 0 }
function GetCursorPosition() return CUR.x, CUR.y end
function UIParent:GetEffectiveScale() return SCREEN.ph / SCREEN.uh end
WowCompanion.pad.geom = nil; WowCompanion.pad.layout()''')


def cursor_at(gx, gy):
    """put the game's cursor at game px (gx, gy), top-left origin"""
    L.execute('CUR.x = %r; CUR.y = SCREEN.ph - %r' % (gx, gy))


d = L.eval('WowCompanion.pad.describe()')
cal = d.calib if d is not None else None
check(cal is not None, "b. describe() has `calib`")
if cal is not None:
    check(cal.x == d.x - 12 and cal.w == 6 and cal.y == d.y, "b. the strip: 6 px wide, 6 px left of the grid, from the first row's top (%s,%s %s)" % (cal.x, cal.y, cal.w))
    n_fit = L.eval('(function() local n = 0; while n < 56 and WowCompanion.pad.fits(n) do n = n + 1 end; return n end)()')
    check(cal.h == n_fit * 2, "b. over every row this layout places (%s px = %d rows)" % (cal.h, n_fit))
    check(cal.n == 0 and cal.p is None, "b. no press yet")

btn = 'WowCompanion.pad.cal.btn'
check(L.eval(btn + ' ~= nil and ' + btn + ':GetScript("OnClick") ~= nil'), "c. the strip is a button with a press script")


def press_strip():
    L.execute('local b = %s; b:GetScript("OnClick")(b, "LeftButton", true)' % btn)


rx_before = L.eval('(WowCompanion.pad.rxDoc() or { n = 0 }).n')
cursor_at(837.0, 16.4)
press_strip()
L.execute('WowCompanion.pad.cal.lastAt = -10')    # the next press is a new tap
cursor_at(837.0, 120.7)
press_strip()
press_strip()                                       # the Thor's second press of one tap: the same spot at once
d = L.eval('WowCompanion.pad.describe()')
check(d.calib.n == 2, "c. two taps counted, the double press once (%s)" % d.calib.n)
check(d.calib.p == "837,16.4 837,120.7", "c. where the game saw them, newest last (%s)" % d.calib.p)
check(L.eval('(WowCompanion.pad.rxDoc() or { n = 0 }).n') == rx_before, "c. a strip press is no pad press (the ring is unchanged)")
L.execute('CALHEARD = 0; WowCompanion.pad.onLayout(function() CALHEARD = CALHEARD + 1 end); WowCompanion.pad.cal.lastAt = -10')
cursor_at(837.0, 60.0)
press_strip()
check(L.eval('CALHEARD') == 1, "c. a strip press re-publishes the grid at once (the feeds' onLayout)")
for i in range(5):
    L.execute('WowCompanion.pad.cal.lastAt = -10')
    cursor_at(837.0, 20.0 + i)
    press_strip()
d = L.eval('WowCompanion.pad.describe()')
check(d.calib.n == 8 and len(d.calib.p.split()) == 4 and d.calib.p.split()[-1] == "837,24", "c. the last 4 only (%s)" % d.calib.p)


def press(row, col):
    return L.eval('''(function()
        local r = WowCompanion.pad.rows["%s"]; local b = r and r.cells and r.cells[%d]
        if not b then return "no cell" end
        local fn = b:GetScript("OnClick"); if not fn then return "no script" end
        b.pressedAt = nil; fn(b, "LeftButton", true); return "ok" end)()''' % (row, col))


row_ui = L.eval('WowCompanion.pad.rows.ui.row')
cursor_at(d.x + 34 * 6 + 3.5, 12 + row_ui * 2 + 1.5)
check(press("ui", 34) == "ok", "d. a press on ui:34")
rx = L.eval('WowCompanion.pad.rxDoc()')
want = "%g,%g" % (d.x + 34 * 6 + 3.5, 12 + row_ui * 2 + 1.5)
check(rx.last.split()[-1] == "ui:34" and rx.at.split()[-1] == want, "d. the ring keeps where it was seen (%s at %s)" % (rx.last.split()[-1], rx.at.split()[-1]))
check(len(rx.at.split()) == len(rx.last.split()), "d. `at` beside `last`, one each")

z = L.eval('(function() for _, z in ipairs(WowCompanion.lay.forbidden()) do if z.why == "pad" then return z end end end)()')
k = 1080 / d.ph
check(z is not None and z.x <= (cal.x - 2) * k + 1e-6 and z.y + z.h >= (cal.y + cal.h) * k - 1e-6, "e. the catcher's band zone covers the strip")

check(L.eval('WowCompanion.pad.nrows') <= 56, "f. still at most 56 rows (the strip is no row)")

print("\n%d failure(s)" % len(FAILS))
sys.exit(1 if FAILS else 0)
