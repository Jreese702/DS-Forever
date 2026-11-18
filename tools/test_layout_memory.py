"""hub66 LAYOUT + CAST BAR MEMORY (WowCompanionHub/Hub.lua), off-device under a stub game.

    python tools/test_layout_memory.py

Checks: the app's layout mask tap and cast-bar tap are kept per character in WowCompanionDB.hubLy with the time; a login puts them
back by itself (no timer), /wcui reset shows everything again and forgets,
another character gets nothing, a later tap still wins, a corrupt table never breaks the load. Needs lupa. Exit 1 on failure."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import test_hud_switch_memory as hm  # noqa: E402

hm.EXTRA += '\nNOWT = 1000000\nfunction time() return NOWT end\n'


def main():
    fails = []

    def chk(name, ok):
        print(("PASS " if ok else "FAIL ") + name)
        if not ok:
            fails.append(name)

    def doc(L, G):
        G.SENT = L.table()
        G.ROWS.uisw(None, 99)
        lst = [G.SENT[i] for i in range(1, len(G.SENT) + 1) if G.SENT[i].type == "hub"]
        return lst[-1].hub if lst else None

    L, G = hm.boot()
    tap = G.ROWS.ui
    d = doc(L, G)
    chk("1: first login: no layout asked (want %s)" % d.want, d.want is None)
    tap(None, 5)
    tap(None, 33)
    m = G.WowCompanionDB.hubLy["Rutger-Mograine"]
    chk("2: mask 5 + cast bar hidden kept with the time (%s %s %s)" % (m.mask, m.cb, m.at), m.mask == 5 and m.cb is True and m.at == 1000000)
    saved = '{ hubLy = { ["Rutger-Mograine"] = { mask = 5, cb = true, at = 1000000 } } }'
    hm.EXTRA = hm.EXTRA.replace('NOWT = 1000000', 'NOWT = 1000600')
    L, G = hm.boot(saved)
    d = doc(L, G)
    chk("3: relog 10 min later: layout put back by itself (want %s)" % d.want, d.want == 5)
    chk("3: cast bar hidden by itself (%s)" % (d.cb and d.cb.hidden), d.cb and d.cb.hidden is True)
    G.ROWS.ui(None, 0)
    G.ROWS.ui(None, 32)
    d = doc(L, G)
    chk("4: a later tap wins (want %s, cb %s)" % (d.want, d.cb and d.cb.hidden), d.want == 0 and not (d.cb and d.cb.hidden))
    hm.EXTRA = hm.EXTRA.replace('NOWT = 1000600', 'NOWT = 1090000')
    L, G = hm.boot(saved)
    d = doc(L, G)
    chk("5: a day later: still put back, no timer (want %s)" % d.want, d.want == 5 and d.cb.hidden is True)
    G.SlashCmdList.WCUI("reset")
    d = doc(L, G)
    chk("5b: /wcui reset: everything shown, cast bar back (want %s cb %s)" % (d.want, d.cb.hidden), d.want == 0 and d.cb.hidden is False)
    chk("5c: /wcui reset forgets the kept entry", G.WowCompanionDB.hubLy["Rutger-Mograine"] is None)
    chk("5d: /wcui reset shows the player frame (alpha %s)" % G.PlayerFrame.alpha, G.PlayerFrame.alpha == 1)
    G.ROWS.ui(None, 5)
    G.SlashCmdList.WCUI("0")
    d = doc(L, G)
    chk("5e: /wcui <mask> still works (want %s)" % d.want, d.want == 0)
    hm.EXTRA = hm.EXTRA.replace('NOWT = 1090000', 'NOWT = 1000600')
    L, G = hm.boot(saved, name="Bank Dea")
    d = doc(L, G)
    chk("6: another character: nothing (want %s)" % d.want, d.want is None)
    try:
        L, G = hm.boot('{ hubLy = { ["Rutger-Mograine"] = { mask = "x", cb = 3, at = 1000500 } } }')
        d = doc(L, G)
        chk("7: corrupt entry ignored (want %s)" % d.want, d.want is None)
    except Exception as e:  # noqa: BLE001
        chk("7: corrupt entry ignored (raised %s)" % e, False)
    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
