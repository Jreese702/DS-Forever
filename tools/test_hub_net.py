"""hub75 THE GAME'S LATENCY FOR THE STALL LINES (issues 582 / 560, 11-23, logging only), off-device: the REAL Hub.lua (the world
files) under tools/wowstub.py.
- the hub document carries net = { h, w }: GetNetStats' home / world latency in ms (a read-only game call);
- it rides along like the shot log: a new latency alone never changes the hub's change key (no send of its own);
- no GetNetStats (or one that throws): no `net`, nothing else changes; nothing of the game's is written (no CVar, no binding).
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


HUB = open(os.path.join(ADDONS, "WowCompanionHub", "Hub.lua"), encoding="utf-8").read()
check(re.search(r'local HV = "hub79"', HUB) is not None and "hub74 THE 1514" in HUB, "a. the tag: Hub hub76 (hub74's note kept)")
check("doc.shotlog, doc.rx, doc.net = nil, nil, nil" in HUB, "a. net is out of the change key, beside the shot log and the presses")

L, errs = boot_world(strict=False)
check(not errs, "the world loads (%s)" % "; ".join(errs)[:200])

L.execute('''_G.__lat = { 1.5, 2.5, 45.4, 60.6 }
    _G.GetNetStats = function() return __lat[1], __lat[2], __lat[3], __lat[4] end
    _G.__writes = 0
    local function count() __writes = __writes + 1 end
    _G.SetCVar = count; _G.SetBinding = count; _G.SetBindingClick = count; _G.SaveBindings = count''')


def sends(lua):
    return L.eval('''(function() local out = {}; local s = WowCompanion.send; WowCompanion.send = function(x) out[#out + 1] = x end
        local ok, e = pcall(function() %s end); WowCompanion.send = s; return out end)()''' % lua)


def hubs(out):
    docs = []
    for i in range(1, len(out) + 1):
        try:
            d = json.loads(out[i])
        except Exception:      # noqa: BLE001
            continue
        if d.get("type") == "hub":
            docs.append(d["hub"])
    return docs


h = hubs(sends('WowCompanion.snapshot("manual")'))
check(len(h) == 1, "b. a heartbeat-like snapshot sends one hub document (%d)" % len(h))
net = h[0].get("net") if h else None
check(net == {"h": 45, "w": 61}, "b. it carries the latency, rounded: %s" % net)

# a new latency alone: no send of its own (the change key leaves it out)
L.execute('__lat[3] = 300; __lat[4] = 320')
again = hubs(sends('WowCompanion.meters.emit()'))
check(len(again) == 0, "c. the latency alone changes nothing that sends (%d sent)" % len(again))
h2 = hubs(sends('WowCompanion.snapshot("manual")'))
check(h2 and h2[0].get("net") == {"h": 300, "w": 320}, "c. the next heartbeat carries the new one (%s)" % (h2 and h2[0].get("net")))

# no call, or one that throws: no `net`, the document as before
L.execute('_G.GetNetStats = nil')
h3 = hubs(sends('WowCompanion.snapshot("manual")'))
check(h3 and "net" not in h3[0] and h3[0].get("hv") == "hub79", "d. no GetNetStats: no net (%s)" % (h3 and sorted(h3[0].keys())[:6]))
L.execute('_G.GetNetStats = function() error("nope") end')
h4 = hubs(sends('WowCompanion.snapshot("manual")'))
check(h4 and "net" not in h4[0], "d. a throwing GetNetStats: no net")
check(L.eval('__writes') == 0, "e. nothing of the game's written (CVars, bindings): %s" % L.eval('__writes'))

print("\n%d failure(s)" % len(FAILS))
sys.exit(1 if FAILS else 0)
