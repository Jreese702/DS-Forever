"""pk1 (the user, 2026-11-12: "we need to make a way to skip a polestar objective, idealy in the home map moduale with X or Y, maybe we
also add a previous step button? idk"; the picks: X Skip, Y Previous = "Undo my last skip", no confirm): Map.lua's WC.pskip, the REAL
file loaded under tools/wowstub.py with a fake Polestar (its Engine.Skip / Unskip / Goto / GetGuide / GetStepIndex / GetStepView,
Polestar.On's PLAYER_SKIPPED_STEP and CharState's saved skips, as Core/Engine.lua and Core/Init.lua behave):
- the "pnav" row's 38 then 10 skips the shown step (once on the stack, though the event and our call both say it), 38 then 11 undoes
  the LAST skip (Unskip: it is the live step again), and repeated Previous walks back skip by skip; nothing left: refused, nothing called;
- a skip made in Polestar's own window is heard (Polestar.On) and undone by Previous too; a step brought back there is dropped;
- the stack is this character's (name-realm) and the guide's; a step doc (pstep ps6) carries sk and u;
- pk3 (the user's pick 2026-11-12, "Work in fights too"): in combat both WORK (Polestar called, answered ok); the answer carries u
  (the skips Previous can undo after it) and the step doc goes out AT ONCE (no wait for the 1 s tick), so Previous is there right
  after a Skip; two quick presses through the REAL pad cells, spaced as the app spaces pnav taps (>= 0.26 s), both land;
- Polestar absent / an older one with no Skip: harmless, sk absent;
- 38 then 8 / 9 (the row holds) and 0-7 (the automation mask) are as before.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import boot, docs, nsent      # noqa: E402

FAKE = r'''
CALLS = {}
local function call(n, ...) CALLS[#CALLS + 1] = n .. "(" .. table.concat({ ... }, ",") .. ")" end
PSDB = { skipped = {} }            -- Polestar's CharState()
LISTEN = {}
GUIDE = { id = "Elwynn", steps = 12 }
CUR = 3                            -- the shown / live step
DONE = { [1] = true, [2] = true }  -- steps 1 and 2 done
local function firstOpen()
    local sk = PSDB.skipped[GUIDE.id] or {}
    for i = 1, GUIDE.steps do if not sk[i] and not (DONE and DONE[i]) then return i end end
end
Polestar = {
    On = function(msg, fn) LISTEN[msg] = LISTEN[msg] or {}; table.insert(LISTEN[msg], fn); return {} end,
    CharState = function() return PSDB end,
    Engine = {
        GetGuide = function() return GUIDE end,
        GetStepIndex = function() return CUR end,
        GetWaypoint = function() return nil end,
        GetStepView = function(i) return { title = "Step " .. i, focus = 1, live = CUR, status = (i == CUR) and "current" or "upcoming",
            goals = { { text = "Do step " .. i, status = "active" } } } end,
        Skip = function()
            call("Skip")
            if not CUR then return nil end
            local s = CUR
            PSDB.skipped[GUIDE.id] = PSDB.skipped[GUIDE.id] or {}
            PSDB.skipped[GUIDE.id][s] = true
            CUR = firstOpen()
            for _, fn in ipairs(LISTEN.PLAYER_SKIPPED_STEP or {}) do fn(GUIDE.id, s) end
            return s
        end,
        Unskip = function(i)
            call("Unskip", i)
            local sk = PSDB.skipped[GUIDE.id]
            if not (sk and sk[i]) then return false end
            sk[i] = nil
            CUR = firstOpen()
            return true
        end,
        Goto = function(i) call("Goto", i); local sk = PSDB.skipped[GUIDE.id]; if sk then sk[i] = nil end; CUR = i end,
    },
}
'''

fails = []


def chk(name, ok):
    print(("ok   " if ok else "FAIL ") + name)
    if not ok:
        fails.append(name)


def tap(L, *cols):
    for c in cols:
        L.execute('WowCompanion.pad.rows.pnav.handler(nil, %d); NOW = NOW + 0.15' % c)


def stack(L, who="Korvash-Realm", gk="Elwynn"):
    return L.eval('(function() local d = WowCompanionDB.pskip; local m = d and d["%s"]; local s = m and m["%s"]; '
                  'if not s then return "" end; return table.concat(s, ",") end)()' % (who, gk))


def last(L, typ, n0):
    ds = docs(L, n0, typ)
    return ds[-1] if ds else None


def run():
    L = boot(["safety", "tappad", "maproom"], pre=FAKE)
    chk("the pnav row is there", L.eval('WowCompanion.pad.rows.pnav ~= nil'))

    # X: skip step 3 -> live 4; once on the stack (the event and our call)
    n0 = nsent(L)
    tap(L, 38, 10)
    a = last(L, "pskip", n0)
    chk("skip: Polestar's Skip called once, the step 4 live", L.eval('CALLS[1]') == "Skip()" and L.eval('#CALLS') == 1 and L.eval('CUR') == 4)
    chk("skip: answered ok with the step and u = 1 (pk3)", a is not None and a["v"] == "pk3" and a["op"] == "skip" and a["ok"] is True
        and a["i"] == 3 and a.get("u") == 1)
    chk("skip: the step once on this character's stack (%s)" % stack(L), stack(L) == "3")
    tap(L, 38, 10)
    chk("skip again: 4 skipped, live 5, stack 3,4", L.eval('CUR') == 5 and stack(L) == "3,4")

    # the step doc: sk and u
    n0 = nsent(L)
    L.execute('WowCompanion.pstep.sig = nil; tick(1)')
    d = last(L, "pstep", n0)
    chk("the step doc is ps6 with sk and u = 2", d is not None and d["v"] == "ps6" and d.get("sk") is True and d.get("u") == 2)

    # Y: undo the LAST skip (4) -> live 4; again -> 3; then nothing
    L.execute('CALLS = {}')
    n0 = nsent(L)
    tap(L, 38, 11)
    a = last(L, "pskip", n0)
    chk("previous: Unskip(4), 4 is the live step again", L.eval('CALLS[1]') == "Unskip(4)" and L.eval('CUR') == 4
        and a["op"] == "prev" and a["ok"] is True and a["i"] == 4 and stack(L) == "3")
    chk("... no Goto (it is the live step already)", all("Goto" not in (L.eval('CALLS[%d]' % i) or "") for i in range(1, L.eval('#CALLS') + 1)))
    tap(L, 38, 11)
    chk("previous again: 3 back, the live step", L.eval('CUR') == 3 and stack(L) == "")
    L.execute('CALLS = {}')
    n0 = nsent(L)
    tap(L, 38, 11)
    a = last(L, "pskip", n0)
    chk("previous with nothing to undo: refused, Polestar not called", a["ok"] is False and a["why"] == "nothing" and L.eval('#CALLS') == 0)
    n0 = nsent(L)
    L.execute('WowCompanion.pstep.sig = nil; tick(1)')
    d = last(L, "pstep", n0)
    chk("... the step doc says u = 0", d.get("sk") is True and d.get("u") == 0)

    # a skip in Polestar's own window is heard (Polestar.On) and undone by Previous
    L.execute('Polestar.Engine.Skip()')
    chk("Polestar's own skip heard: on the stack", stack(L) == "3" and L.eval('CUR') == 4)
    tap(L, 38, 11)
    chk("... Previous brings it back", L.eval('CUR') == 3 and stack(L) == "")

    # a step brought back in Polestar's window is dropped: Previous goes to the skip before it
    tap(L, 38, 10)              # 3 skipped, live 4
    tap(L, 38, 10)              # 4 skipped, live 5
    L.execute('PSDB.skipped.Elwynn[4] = nil; CUR = 4')      # brought back there
    L.execute('WowCompanion.pstep.sig = nil')
    n0 = nsent(L)
    L.execute('tick(1)')
    d = last(L, "pstep", n0)
    chk("a step un-skipped in Polestar's window leaves the count (u = 1)", d.get("u") == 1 and stack(L) == "3")
    L.execute('CALLS = {}; DONE[2] = nil')      # an earlier step open again (its quest abandoned): it would stand in front
    tap(L, 38, 11)
    chk("... Previous undoes 3, shown with Goto (step 2 open in front of it)", L.eval('CALLS[1]') == "Unskip(3)"
        and L.eval('CALLS[2]') == "Goto(3)" and L.eval('CUR') == 3)
    L.execute('DONE[2] = true')

    # per character, per guide
    tap(L, 38, 10)
    L.execute('WHO = "Alt"')
    n0 = nsent(L)
    tap(L, 38, 11)
    a = last(L, "pskip", n0)
    chk("another character: its own (empty) stack, nothing undone", a["ok"] is False and stack(L) == "3" and stack(L, "Alt-Realm") == "")
    L.execute('WHO = "Korvash"; GUIDE = { id = "Westfall", steps = 12 }')
    tap(L, 38, 11)
    chk("another guide: its own stack, Elwynn's kept", stack(L) == "3" and stack(L, gk="Westfall") == "")
    L.execute('GUIDE = { id = "Elwynn", steps = 12 }')

    # pk3: in combat both work (the user's pick "Work in fights too")
    L.execute('COMBAT = true; CALLS = {}')
    chk("(the stub is in lockdown)", L.eval('InCombatLockdown()') is True)
    cur0 = L.eval('CUR')
    n0 = nsent(L)
    tap(L, 38, 10)
    ans = docs(L, n0, "pskip")
    chk("in combat: Skip works (Polestar's Skip called, answered ok)", len(ans) == 1 and ans[0]["ok"] is True and L.eval('CALLS[1]') == "Skip()"
        and L.eval('CUR') != cur0)
    n0 = nsent(L)
    tap(L, 38, 11)
    ans = docs(L, n0, "pskip")
    chk("in combat: Previous works (the skip undone)", len(ans) == 1 and ans[0]["ok"] is True and L.eval('CUR') == cur0)
    chk("... no answer says combat", all(x.get("why") != "combat" for x in docs(L, 0, "pskip")))
    L.execute('COMBAT = false')

    # pk3: the step doc at once after a skip (no tick), its u already counting it; the answer's u the same
    L.execute('WowCompanion.pstep.sig = "x"; WowCompanion.pstep.sentAt = NOW')   # (a doc just sent: the tick would wait)
    n0 = nsent(L)
    u0 = stack(L).count(",") + 1 if stack(L) else 0
    tap(L, 38, 10)
    seq = [d["type"] for d in docs(L, n0) if d.get("type") in ("pstep", "pskip")]
    st = docs(L, n0, "pstep")
    a = last(L, "pskip", n0)
    chk("skip: the step doc at once, before the answer (%s)" % seq, seq[:2] == ["pstep", "pskip"])
    chk("... its u counts the new skip (%s -> %s), the answer's u too" % (u0, st[-1].get("u") if st else None),
        st and st[-1].get("u") == u0 + 1 and a.get("u") == u0 + 1)
    n0 = nsent(L)
    tap(L, 38, 11)
    st = docs(L, n0, "pstep")
    a = last(L, "pskip", n0)
    chk("previous: the step doc at once with u back to %d, the answer's u too" % u0, st and st[-1].get("u") == u0 and a.get("u") == u0)
    n0 = nsent(L)
    if u0 == 0:
        tap(L, 38, 11)
        a = last(L, "pskip", n0)
        chk("a refused Previous still says u = 0", a["ok"] is False and a.get("u") == 0)

    # pk3: a quick double press through the REAL pad cells (TapPad's press + its 0.25 s repeat guard), spaced as the app spaces every
    # pnav tap (TapSpacing.MIN_MS 260 ms between two taps of one cell, across batches): both skips land
    def press(col, dt):
        L.execute('local c = WowCompanion.pad.rows.pnav.cells[%d]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false); '
                  'NOW = NOW + %f' % (col, dt))
    L.execute('pcall(WowCompanion.pad.layout)')                 # (the pad placed: its cells built, as at login)
    chk("(the pnav cells are real buttons)", L.eval('WowCompanion.pad.rows.pnav.cells[38] ~= nil and WowCompanion.pad.rows.pnav.cells[10] ~= nil'))
    L.execute('CALLS = {}')
    cur0 = L.eval('CUR')
    n0 = nsent(L)
    press(38, 0.12); press(10, 0.14)          # batch 1: 38 at 0, 10 at 0.12; the next 38 at 0.26
    press(38, 0.12); press(10, 0.30)
    ans = [x for x in docs(L, n0, "pskip") if x["op"] == "skip"]
    chk("two quick skips (0.26 s apart) both land (%d answers, %d Skip calls)" % (len(ans), L.eval('#CALLS')),
        len(ans) == 2 and all(x["ok"] for x in ans) and L.eval('#CALLS') == 2)
    L.execute('CALLS = {}')
    n0 = nsent(L)
    press(38, 0.12); press(11, 0.14)
    press(38, 0.12); press(11, 0.30)
    ans = [x for x in docs(L, n0, "pskip") if x["op"] == "prev"]
    chk("two quick previous presses both land, the step back to %s" % cur0, len(ans) == 2 and all(x["ok"] for x in ans) and L.eval('CUR') == cur0)
    # (why the app spaces them: 0.2 s apart the second 38 is TapPad's repeat guard's, and that press is lost)
    L.execute('CALLS = {}')
    n0 = nsent(L)
    press(38, 0.1); press(10, 0.1)
    press(38, 0.1); press(10, 0.5)
    chk("(unspaced, 0.2 s: the second press is lost, as the app's spacing is for)", L.eval('#CALLS') == 1)

    # the row's other words are as before
    L.execute('WowCompanion.pnav.psAt = nil')
    tap(L, 38, 8)
    chk("38, 8: the row holds (as before)", L.eval('WowCompanion.pnav.psOn()') is True)
    tap(L, 38, 9)
    chk("38, 9: it does not", L.eval('WowCompanion.pnav.psOn()') is False)
    L.execute('CALLS = {}')
    tap(L, 10)
    tap(L, 11)
    chk("10 / 11 without 38: nothing", L.eval('#CALLS') == 0)
    L.execute('NOW = NOW + 3')
    tap(L, 38)
    L.execute('NOW = NOW + 3')
    tap(L, 10)
    chk("10 more than 2 s after 38: nothing", L.eval('#CALLS') == 0)

    # an older Polestar (no Skip) and none at all: harmless
    for pre, name in (('Polestar.Engine.Skip = nil', "an older Polestar (no Skip)"), ('Polestar = nil', "no Polestar")):
        M = boot(["safety", "tappad", "maproom"], pre=FAKE + pre)
        n0 = nsent(M)
        ok = True
        try:
            tap(M, 38, 10)
            tap(M, 38, 11)
            M.execute('WowCompanion.pstep.sig = nil; tick(1)')
        except Exception as e:        # noqa: BLE001
            ok = False
            print("  error:", e)
        ans = docs(M, n0, "pskip")
        st = docs(M, n0, "pstep")
        chk("%s: no error, both refused, no sk" % name, ok and len(ans) == 2 and all(x["ok"] is False and x["why"] == "no Polestar" for x in ans)
            and all("sk" not in x for x in st))


run()
print("%d failed" % len(fails) if fails else "all ok")
sys.exit(1 if fails else 0)
