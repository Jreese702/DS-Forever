"""pad23 / hub69 WHERE A TAP LANDED (the user, 2026-11-18, test 1492 streamed: "it looked like it was doing a lot of clicks again and
also the UI didnt hide"; the "ui" row's restore and layout taps were never answered on the PC while the "uisw" row's were), off-device:
the REAL TapPad.lua and Hub.lua (the world files) under tools/wowstub.py.
- every cell press is kept in TapPad's ring (pad.rxDoc: the count and the last 8 "row:col", newest last);
- Hub's document carries it as `rx`, riding along (a press alone never changes the hub's change key: no extra send);
- describe() has no `off` while every row stands where the grid says, and names a row whose first cell was moved, with its real top.
Needs lupa. Exit code 1 on any failure."""
import json
import os
import re
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
HUB = open(os.path.join(ADDONS, "WowCompanionHub", "Hub.lua"), encoding="utf-8").read()
check((int(re.search(r'local PADV = "pad(\d+)"', TAPPAD).group(1)) >= 24 and "pad23 WHERE A TAP LANDED" in TAPPAD) and re.search(r'local HV = "hub(\d+)"', HUB) and int(re.search(r'local HV = "hub(\d+)"', HUB).group(1)) >= 70 and "hub69 WHERE THE TAPS LANDED" in HUB, "a. TapPad pad24 (pad23 kept in its note), Hub hub70 (hub69 kept)")
check("so why is it still clicking all over after that then?" in HUB, "a. hub70's note quotes the user")
check("it looked like it was doing a lot of clicks again and also the UI didnt hide" in TAPPAD
      and "it looked like it was doing a lot of clicks again and also the UI didnt hide" in HUB, "a. both notes quote the user")

has = L.eval('WowCompanion.pad.rows.ui ~= nil and WowCompanion.pad.rows.uisw ~= nil')
check(has, "b. the hub's rows are registered")
check(L.eval('WowCompanion.pad.rxDoc() == nil'), "b. no press yet: no ring")


def press(row, col):
    return L.eval('''(function()
        local r = WowCompanion.pad.rows["%s"]; local b = r and r.cells and r.cells[%d]
        if not b then return "no cell" end
        local fn = b:GetScript("OnClick"); if not fn then return "no script" end
        fn(b, "LeftButton", true); return "ok" end)()''' % (row, col))


check(press("ui", 34) == "ok", "c. a press on ui:34")
d = L.eval('WowCompanion.pad.rxDoc()')
check(d is not None and d.n == 1 and d.last == "ui:34", "c. the ring says ui:34 (%s)" % (d and d.last))
for c in range(1, 10):
    press("uisw", c)
d = L.eval('WowCompanion.pad.rxDoc()')
check(d.n == 10 and d.last == " ".join("uisw:%d" % c for c in range(2, 10)), "c. the last 8, newest last (%s)" % d.last)

# hub69: the document carries the ring; a press alone is never a send of its own
doc = L.eval('(function() local out; local s = WowCompanion.send; WowCompanion.send = function(x) out = x end; '
             'local ok = pcall(WowCompanion.snapshot, "manual"); WowCompanion.send = s; return out end)()')
check("rx = WC.pad and type(WC.pad.rxDoc)" in HUB and "doc.shotlog, doc.rx = nil, nil" in HUB, "d. hub's document: rx, out of the change key")

# hub70: when the count moved, one small "padrx" document of its own; nothing while it stands still
def rx_tick():
    return L.eval('(function() local out = {}; local s = WowCompanion.send; WowCompanion.send = function(x) out[#out + 1] = x end; '
                  'local ok, r = pcall(WowCompanion.hub.rxTick); WowCompanion.send = s; return { ok = ok, r = r, n = #out, x = out[1] } end)()')
t = rx_tick()
check(t.ok and t.r is True and t.n == 1 and '"type":"padrx"' in (t.x or "") and '"n":10' in (t.x or ""), "d. hub70: the ring's word on its own (%s)" % (t.x and t.x[:80]))
t = rx_tick()
check(t.ok and t.r is False and t.n == 0, "d. hub70: nothing again while the count stands")
press("ui", 31)
t = rx_tick()
check(t.ok and t.r is True and '"n":11' in (t.x or ""), "d. hub70: the next press, at once")


# the stub has no GetTop: a frame's top from its TOPLEFT point (UIParent's own top = its height)
L.execute('''function Frame:SetPoint(...) self._pts = { ... } end
function Frame:ClearAllPoints() self._pts = nil end
function Frame:GetPoint() local p = self._pts or {}; return p[1], p[2], p[3], p[4], p[5] end
function Frame:GetTop()
    if self == UIParent then return UIParent:GetHeight() end
    local p = self._pts; if not p or p[1] ~= "TOPLEFT" then return nil end
    return UIParent:GetHeight() + (p[5] or 0) end
WowCompanion.pad.geom = nil; WowCompanion.pad.layout()''')
desc = L.eval('WowCompanion.pad.describe()')
check(desc is not None and desc.off is None, "e. every row where the grid says: no `off`")
L.execute('''local b = WowCompanion.pad.rows.ui.cells[0]; local p = { b:GetPoint(1) }
    b:ClearAllPoints(); b:SetPoint("TOPLEFT", UIParent, "TOPLEFT", p[4] or 0, (p[5] or 0) - 20)''')
desc = L.eval('WowCompanion.pad.describe()')
off = desc.off if desc is not None else None
check(off is not None and off.ui is not None, "e. a moved row is named in `off` (%s)" % (off and off.ui))

print("\n%d failure(s)" % len(FAILS))
sys.exit(1 if FAILS else 0)
