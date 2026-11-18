"""hub67 ONE-TAP LAYOUT + CAST BAR MEMORY (WowCompanionHub/Hub.lua; external PR #532 by mummigast, reworked to the user's one-tap
rule, 2026-11-18), off-device under a stub game.

    python tools/test_layout_memory.py

Checks: the app's layout mask tap and cast-bar tap are kept per character in WowCompanionDB.hubLy; a login WITHOUT the app's restore
tap puts nothing back (the normal UI); the restore tap ("ui" column 34) puts them back; a restore tap in combat waits for the fight's
end; /wcui reset shows everything again and forgets; another character gets nothing; a later tap still wins; a corrupt entry never
breaks the load. Needs lupa. Exit 1 on failure."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import test_hud_switch_memory as hm  # noqa: E402


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
    chk("2: mask 5 + cast bar hidden kept (%s %s)" % (m.mask, m.cb), m.mask == 5 and m.cb is True)
    saved = '{ hubLy = { ["Rutger-Mograine"] = { mask = 5, cb = true } } }'
    L, G = hm.boot(saved)
    d = doc(L, G)
    chk("3: relog without the app's tap: nothing put back (want %s, cb %s)" % (d.want, d.cb and d.cb.hidden),
        d.want is None and not (d.cb and d.cb.hidden))
    hm.restore(G)
    d = doc(L, G)
    chk("3b: the restore tap: layout put back (want %s)" % d.want, d.want == 5)
    chk("3b: ... cast bar hidden (%s), rst.ly %s" % (d.cb and d.cb.hidden, d.rst.ly), d.cb and d.cb.hidden is True and d.rst.ly is True)
    G.ROWS.ui(None, 0)
    G.ROWS.ui(None, 32)
    d = doc(L, G)
    chk("4: a later tap wins (want %s, cb %s)" % (d.want, d.cb and d.cb.hidden), d.want == 0 and not (d.cb and d.cb.hidden))
    m = G.WowCompanionDB.hubLy["Rutger-Mograine"]
    chk("4: ... and is kept (mask %s, cb %s)" % (m.mask, m.cb), m.mask == 0 and m.cb is False)
    # 5 combat: the restore tap waits for the fight's end
    L, G = hm.boot(saved, combat=True)
    hm.restore(G)
    d = doc(L, G)
    chk("5: restore tap in combat: held (want %s, q %s)" % (d.want, d.rst.q), d.want is None and d.rst.q is True)
    G.COMBAT = False
    hm.fire(G, "PLAYER_REGEN_ENABLED")
    d = doc(L, G)
    chk("5: after the fight: put back (want %s, cb %s)" % (d.want, d.cb.hidden), d.want == 5 and d.cb.hidden is True)
    # 6 /wcui reset
    G.SlashCmdList.WCUI("reset")
    d = doc(L, G)
    chk("6: /wcui reset: everything shown, cast bar back (want %s cb %s)" % (d.want, d.cb.hidden), d.want == 0 and d.cb.hidden is False)
    chk("6: /wcui reset forgets the kept entry", G.WowCompanionDB.hubLy["Rutger-Mograine"] is None)
    chk("6: /wcui reset shows the player frame (alpha %s)" % G.PlayerFrame.alpha, G.PlayerFrame.alpha == 1)
    hm.restore(G)
    d = doc(L, G)
    chk("6: a restore after the reset puts nothing back (want %s)" % d.want, d.want == 0)
    G.ROWS.ui(None, 5)
    G.SlashCmdList.WCUI("0")
    d = doc(L, G)
    chk("6: /wcui <mask> still works (want %s)" % d.want, d.want == 0)
    # 7 another character
    L, G = hm.boot(saved, name="Bank Dea")
    hm.restore(G)
    d = doc(L, G)
    chk("7: another character: nothing (want %s)" % d.want, d.want is None)
    # 8 corrupt
    try:
        L, G = hm.boot('{ hubLy = { ["Rutger-Mograine"] = { mask = "x", cb = 3 } } }')
        hm.restore(G)
        d = doc(L, G)
        chk("8: corrupt entry ignored (want %s, cb %s)" % (d.want, d.cb and d.cb.hidden), d.want is None and not (d.cb and d.cb.hidden))
        L, G = hm.boot('{ hubLy = { ["Rutger-Mograine"] = { mask = 99 } } }')
        hm.restore(G)
        d = doc(L, G)
        chk("8: a mask out of range ignored (want %s)" % d.want, d.want is None)
    except Exception as e:  # noqa: BLE001
        chk("8: corrupt entry ignored (raised %s)" % e, False)
    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
