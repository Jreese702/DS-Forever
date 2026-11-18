"""hub67 ONE-TAP SWITCH MEMORY (WowCompanionHub/Hub.lua; external PR #532 by mummigast, reworked to the user's one-tap rule,
2026-11-18), off-device: Hub.lua under a stub game.

    python tools/test_hud_switch_memory.py

Checks: a switch tap that took is kept per character in WowCompanionDB.hubSw (one column per OFF/ON pair, the last one wins); an
unknown column and Auto Shot (the game's own option) are not kept. A fresh load (a new Lua state = a login) with the saved table
applies NOTHING by itself (WoW started on the PC without the app shows the normal UI); the app's ONE restore tap ("ui" column 34)
replays the switches, so the hub document says `set` from the start and the frames are as chosen; another character's switches are
not replayed; a restore tap in combat waits for PLAYER_REGEN_ENABLED; a tap after the replay still wins and is kept; a corrupt entry
is skipped. Needs lupa. Exit code 1 on any failure."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import test_compass as tc  # noqa: E402
import test_hud_switches as th  # noqa: E402

HUB = th.HUB
RESTORE = 34

EXTRA = r'''
PLAYER_NAME, REALM = "Rutger", "Mograine"
COMBAT = false
function UnitName() return PLAYER_NAME end
function GetRealmName() return REALM end
function InCombatLockdown() return COMBAT end
TIMERS = {}
C_Timer = { After = function(s, fn) TIMERS[#TIMERS + 1] = fn end, NewTicker = function() return { Cancel = function() end } end }
function RUN_TIMERS() local t = TIMERS; TIMERS = {}; for _, fn in ipairs(t) do fn() end end
'''


def fire(G, ev):
    for i in range(1, len(G.MADE) + 1):
        f = G.MADE[i]
        if f.OnEvent:
            f.OnEvent(f, ev)


def boot(db=None, name="Rutger", combat=False):
    """one 'login': a fresh Lua state, the saved table as the game would hand it over, PLAYER_LOGIN then PLAYER_ENTERING_WORLD
    (and every timer the load started): no restore tap yet"""
    L = tc.lupa.LuaRuntime()
    L.execute(th.STUB + EXTRA + r'''
        MADE = {}
        local base = CreateFrame
        function CreateFrame(...) local f = base(...); MADE[#MADE + 1] = f; return f end
    ''')
    G = L.globals()
    G.PLAYER_NAME = name
    G.COMBAT = combat
    if db is not None:
        L.execute("WowCompanionDB = " + db)
    L.execute(open(HUB, encoding="utf-8").read().replace("local ADDON = ...", "local ADDON = 'WowCompanionHub'", 1))
    for ev in ("PLAYER_LOGIN", "PLAYER_ENTERING_WORLD"):
        fire(G, ev)
    G.RUN_TIMERS()
    return L, G


def restore(G):
    """the app's one restore tap"""
    G.ROWS.ui(None, RESTORE)


def dump(L):
    """WowCompanionDB.hubSw as a Lua literal, as SavedVariables would write it"""
    return L.eval(r'''(function()
        local out = { "{ hubSw = {" }
        for k, m in pairs((WowCompanionDB or {}).hubSw or {}) do
            out[#out + 1] = string.format("[%q] = {", k)
            for p, c in pairs(m) do out[#out + 1] = string.format("[%q] = %d,", p, c) end
            out[#out + 1] = "},"
        end
        out[#out + 1] = "} }"
        return table.concat(out, " ")
    end)()''')


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

    # 1 a first login: nothing kept yet, nothing set; the document offers the restore column
    L, G = boot()
    SW = G.WowCompanion.hub.SW
    d = doc(L, G)
    chk("1: first login, nothing kept: player switch not set (%s)" % (d.sw.set and d.sw.set.player), not (d.sw.set and d.sw.set.player))
    chk("1: the hub document offers the restore tap (rst.col %s, n %s)" % (d.rst and d.rst.col, d.rst and d.rst.n),
        d.rst is not None and d.rst.col == RESTORE and d.rst.n == 0)
    # 2 the app's taps are kept per character, one column per pair, the last wins
    for c in (SW.PLAYER_SHOW, SW.PLAYER_HIDE, SW.TARGET_HIDE, SW.NP_ON, SW.PRD_ON, SW.SHOT_ON):
        G.ROWS.uisw(None, c)
    G.ROWS.uisw(None, 99)
    m = G.WowCompanionDB.hubSw["Rutger-Mograine"]
    chk("2: kept per character: player pair = PLAYER_HIDE (%s)" % m["0"], m["0"] == SW.PLAYER_HIDE)
    chk("2: target, nameplates, personal bar kept (%s %s %s)" % (m["1"], m["2"], m["3"]),
        m["1"] == SW.TARGET_HIDE and m["2"] == SW.NP_ON and m["3"] == SW.PRD_ON)
    chk("2: Auto Shot (game's own option) NOT kept (%s)" % m[str(SW.SHOT_ON // 2)], m[str(SW.SHOT_ON // 2)] is None)
    chk("2: an unknown column (99) NOT kept (%s)" % m["49"], m["49"] is None)
    saved = dump(L)
    # 3 THE ONE-TAP RULE: the next login WITHOUT the app's tap applies nothing (WoW on the PC: the normal UI)
    L, G = boot(saved)
    d = doc(L, G)
    chk("3: login without the app's tap: nothing applied (player / target alpha %s / %s)" % (G.PlayerFrame.alpha, G.TargetFrame.alpha),
        G.PlayerFrame.alpha == 1 and G.TargetFrame.alpha == 1)
    chk("3: ... nothing set (player %s, np %s), no restore done (n %s)" % (d.sw.set and d.sw.set.player, d.sw.set and d.sw.set.np, d.rst.n),
        not (d.sw.set and d.sw.set.player) and not (d.sw.set and d.sw.set.np) and d.rst.n == 0)
    G.RUN_TIMERS()
    chk("3: ... not later either (player alpha %s)" % G.PlayerFrame.alpha, G.PlayerFrame.alpha == 1)
    # 4 the app's one restore tap: set from the start, frames as chosen
    restore(G)
    d = doc(L, G)
    chk("4: the restore tap: player + target hidden (alpha %s / %s)" % (G.PlayerFrame.alpha, G.TargetFrame.alpha),
        G.PlayerFrame.alpha == 0 and G.TargetFrame.alpha == 0)
    chk("4: the hub document says set for player, target, np, prd (%s %s %s %s)" % (d.sw.set.player, d.sw.set.target, d.sw.set.np, d.sw.set.prd),
        d.sw.set.player and d.sw.set.target and d.sw.set.np and d.sw.set.prd)
    chk("4: ... and the values (%s %s np %s prd %s)" % (d.sw.player, d.sw.target, d.sw.np, d.sw.prd),
        d.sw.player == "hidden" and d.sw.target == "hidden" and d.sw.np is True and d.sw.prd is True)
    chk("4: Auto Shot not replayed (shot set %s)" % d.sw.set.shot, not d.sw.set.shot)
    chk("4: rst says done (n %s, sw %s, q %s)" % (d.rst.n, d.rst.sw, d.rst.q), d.rst.n == 1 and d.rst.sw == 4 and not d.rst.q)
    # 5 a tap after the replay still wins and is kept; a second restore tap keeps it
    G.ROWS.uisw(None, SW.PLAYER_SHOW)
    chk("5: a later tap wins (player alpha %s) and is kept (%s)" % (G.PlayerFrame.alpha, G.WowCompanionDB.hubSw["Rutger-Mograine"]["0"]),
        G.PlayerFrame.alpha == 1 and G.WowCompanionDB.hubSw["Rutger-Mograine"]["0"] == SW.PLAYER_SHOW)
    restore(G)
    chk("5: a repeated restore tap keeps the later choice (player alpha %s)" % G.PlayerFrame.alpha, G.PlayerFrame.alpha == 1)
    # 6 another character: not replayed, even with the tap
    L, G = boot(saved, name="Bank Dea")
    restore(G)
    d = doc(L, G)
    chk("6: another character: nothing replayed (player alpha %s, set %s)" % (G.PlayerFrame.alpha, d.sw.set and d.sw.set.player),
        G.PlayerFrame.alpha == 1 and not (d.sw.set and d.sw.set.player))
    # 7 a restore tap in combat: held for the fight's end
    L, G = boot(saved, combat=True)
    restore(G)
    d = doc(L, G)
    chk("7: in combat: not replayed yet (player alpha %s), held (q %s, n %s)" % (G.PlayerFrame.alpha, d.rst.q, d.rst.n),
        G.PlayerFrame.alpha == 1 and d.rst.q is True and d.rst.n == 0)
    G.COMBAT = False
    fire(G, "PLAYER_REGEN_ENABLED")
    d = doc(L, G)
    chk("7: after the fight: replayed (player alpha %s, n %s, q %s)" % (G.PlayerFrame.alpha, d.rst.n, d.rst.q),
        G.PlayerFrame.alpha == 0 and d.rst.n == 1 and not d.rst.q)
    # 7b combat without a restore tap: the fight's end applies nothing either
    L, G = boot(saved, combat=True)
    G.COMBAT = False
    fire(G, "PLAYER_REGEN_ENABLED")
    chk("7b: a fight's end without the tap: nothing applied (player alpha %s)" % G.PlayerFrame.alpha, G.PlayerFrame.alpha == 1)
    # 8 a corrupt saved table never breaks the load or the restore
    try:
        L, G = boot('{ hubSw = { ["Rutger-Mograine"] = { ["0"] = "x", ["1"] = 99, ["5"] = 2, [7] = 1, ["2"] = 4 } } }')
        restore(G)
        d = doc(L, G)
        chk("8: corrupt entries skipped, the good one applied (player alpha %s, np %s, sw %s)" % (G.PlayerFrame.alpha, d.sw.np, d.rst.sw),
            G.PlayerFrame.alpha == 1 and d.sw.set.np and d.sw.np is False and d.rst.sw == 1)
        L, G = boot('{ hubSw = { ["Rutger-Mograine"] = "junk" }, hubLy = 7 }')
        restore(G)
        chk("8: a non-table entry ignored (player alpha %s)" % G.PlayerFrame.alpha, G.PlayerFrame.alpha == 1)
    except Exception as e:  # noqa: BLE001
        chk("8: corrupt entries ignored (raised %s)" % e, False)

    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
