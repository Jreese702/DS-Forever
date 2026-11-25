package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HOLD TO CAST ROUND 1533, THE FIRE AT THE THRESHOLD (PadMap m199; the user on test 1532, 2026-11-25: "I also want this to cast right
 * when the hold bar is filled, not when its released"): [HoldRelease], the launcher's engine (pure). A press the add-on says it KEPT (its
 * keep word) is written as up for the game at its fire point F = n x (200 + f) + f + 10 ms while still held; letting go afterwards
 * changes nothing; a tap's release, a press with no keep word and B are never touched; the triggers, the shoulders and every other bit
 * pass as they are. And HoldCast's half: the words, the picture at the fire, the SHORT correction.
 */
class HoldReleaseTest {
    private val X = HoldRelease.X
    private val Y = HoldRelease.Y
    private val A = HoldRelease.A
    private val UP = HoldRelease.UP

    private fun engine(cn: Int? = 3, ft: Int? = 17) = HoldRelease().apply { this.cn = cn; this.ft = ft }
    private fun xDown(e: HoldRelease) = HoldRelease.isDown(X, HoldRelease.outButtons(1 shl 2, e.forcedMask), BooleanArray(4))

    private val gates0 = HoldCast.gates
    @After fun clean() {
        HoldCast.gates = gates0
        HoldCast.padReset(true); HoldCast.release.cn = null; HoldCast.release.ft = null; HoldCast.mapWhy = ""
        HoldCast.watch.reset(); HoldCast.watch.map = emptySet(); HoldCast.takeover.clear()
        HoldCast.clock = { android.os.SystemClock.uptimeMillis() }; HoldCast.bandClock = { android.os.SystemClock.elapsedRealtime() }
    }

    // 1
    @Test fun aKeptPressIsLetGoForTheGameAtItsFirePoint() {
        val e = engine()
        val fires = ArrayList<Pair<Int, Int>>()
        e.onFire = { p, l -> fires += p to l }
        e.edge(X, true, 1000)
        assertTrue("the keep word for X (PAD3) confirms the press", e.keep("PAD3", 5, 0, 3, 1150))
        assertEquals("the fire point: 1000 + F(3, 17) = 1678", 1678L, e.nextAt())
        assertFalse(e.due(1677)); assertTrue("still down for the game at 1677", xDown(e))
        assertTrue("the fire point: the output changed", e.due(1678))
        assertFalse("X reads UP for the game at 1678 (still physically held)", xDown(e))
        assertEquals(listOf(5 to 0), fires)
        assertFalse("once", e.due(1700)); assertEquals(1, fires.size)
        assertTrue(e.forcedPos(5))
    }

    // 2
    @Test fun noKeepWordNothingIsTouched() {
        val e = engine()
        e.edge(X, true, 1000)
        for (t in listOf(1500L, 1678L, 1978L, 2100L)) { e.due(t); assertTrue("no keep word: X down at $t", xDown(e)) }
        assertEquals(HoldRelease.NEVER, e.nextAt())
        assertFalse("a keep after its grace: nothing", e.keep("PAD3", 5, 0, 3, 2100))
        e.edge(X, false, 2200)
        assertEquals("the physical up passes in the same call (nothing forced)", 0, e.forcedMask)
    }

    // 3
    @Test fun aLateKeepWordFiresOnArrivalWithinItsGrace() {
        val e = engine()
        e.edge(X, true, 1000)
        assertTrue("keep at F + 120", e.keep("PAD3", 5, 0, 3, 1678 + 120))
        assertTrue("fires at once", e.due(1678 + 120))
        assertFalse(xDown(e))
        val l = engine()
        l.edge(X, true, 1000)
        assertFalse("keep at F + 301: never", l.keep("PAD3", 5, 0, 3, 1678 + 301))
        l.due(1678 + 301); assertTrue(xDown(l))
    }

    // 4
    @Test fun aTapPassesInTheSameCall() {
        val e = engine()
        e.edge(X, true, 1000); e.keep("PAD3", 5, 0, 3, 1150)
        e.edge(X, false, 1300)
        assertEquals("released at 1300: no mask, no timer", 0, e.forcedMask)
        assertEquals(HoldRelease.NEVER, e.nextAt())
        assertFalse(e.due(1678))
    }

    // 5
    @Test fun theReleaseAfterTheFireIsSwallowed() {
        val e = engine()
        e.edge(X, true, 1000); e.keep("PAD3", 5, 0, 3, 1100); e.due(1678)
        assertFalse(xDown(e))
        e.edge(X, false, 2000)
        assertEquals("the physical up after the fire: nothing new for the game (it read up already)", 0, e.forcedMask)
        assertEquals(HoldRelease.IDLE, e.state(X))
        e.edge(X, true, 2500)
        assertTrue("the next down passes at once", xDown(e))
    }

    // 6
    @Test fun neverB() {
        val e = engine()
        e.edge(A, true, 1000)
        assertFalse("PAD2 (B): refused", e.keep("PAD2", 8, 0, 3, 1100))
        assertFalse("position 7 (B): refused", e.keep(null, 7, 0, 3, 1100))
        assertFalse("B has no control", HoldRelease.ctlOfKey("PAD2") >= 0 || HoldRelease.ctlOfPos(7) >= 0)
        assertEquals("the mask never touches B's bit", 0, HoldRelease.BUTTON_BITS and 2)
    }

    // 7
    @Test fun aStaleKeepIsNotThisPress() {
        val e = engine()
        // tap 1 (down 1000, up 1100); press 2 down at 1200; tap 1's keep and rel come after it
        e.edge(X, true, 1000); e.edge(X, false, 1100); e.edge(X, true, 1200)
        assertTrue(e.keep("PAD3", 5, 0, 3, 1250))                     // tap 1's keep, late
        e.closed(5, 1260)                                               // tap 1's rel word
        assertEquals("not confirmed until its own keep", HoldRelease.HELD, e.state(X))
        assertEquals(HoldRelease.NEVER, e.nextAt())
        assertTrue("press 2's own keep", e.keep("PAD3", 5, 0, 3, 1400))
        assertEquals(1200 + 678L, e.nextAt())
        // a non-kept down word for that position closes the press
        e.closed(5, 1450)
        assertEquals(HoldRelease.HELD, e.state(X))
        assertFalse(e.due(1878)); assertTrue(xDown(e))
    }

    // 8
    @Test fun theGatesStopIt() {
        for (why in listOf("party", "box", "loading")) {
            HoldCast.release.reset(); HoldCast.release.cn = 3; HoldCast.release.ft = 17
            HoldCast.mapWhy = why
            val e = HoldCast.release
            e.edge(X, true, 1000); e.keep("PAD3", 5, 0, 3, 1100)
            // the real gates (PlayModes LOCAL, no pop-up): the holdmap's why
            HoldCast.gates = { HoldCast.mapWhy != "party" && HoldCast.mapWhy != "box" && HoldCast.mapWhy != "loading" }
            assertFalse("why $why: the release decides", e.due(1678)); assertTrue(xDown(e))
        }
        HoldCast.mapWhy = "on"
        for (closed in listOf("stream", "popup", "wheel")) {
            val e = engine()
            e.gatesOpen = { false }
            e.edge(X, true, 1000); e.keep("PAD3", 5, 0, 3, 1100)
            assertFalse("$closed: the release decides", e.due(1678)); assertTrue(xDown(e))
        }
        val p = engine()
        p.edge(X, true, 1000); p.keep("PAD3", 5, 0, 3, 1100); p.pause()
        assertFalse("paused: no fire", p.due(1678)); assertTrue(xDown(p))
        val src = java.io.File(listOf(java.io.File("../companion"), java.io.File("companion")).first { it.isDirectory },
            "app/src/main/java/com/jrees/wowcompanion/HoldCast.kt").readText()
        val g = src.substringAfter("var gates: () -> Boolean = {").substringBefore("\n    }")
        assertTrue("the real gates: STREAM, party / box / loading, our pop-ups and wheels",
            g.contains("PlayModes.current != PlayMode.STREAM") && g.contains("mapWhy != \"party\"") && g.contains("mapWhy != \"box\"")
                && g.contains("mapWhy != \"loading\"") && g.contains("SetupBridge.holdsGamePad()"))
    }

    // 9
    @Test fun twoKeptButtonsEachAtItsOwnPoint() {
        val e = engine()
        e.edge(X, true, 1000); e.keep("PAD3", 5, 0, 3, 1100)
        e.edge(Y, true, 1300); e.keep("PAD4", 6, 1, 3, 1400)
        assertEquals(1678L, e.nextAt())
        assertTrue(e.due(1678))
        val m1 = e.forcedMask
        assertEquals("X forced, Y still down", HoldRelease.bitOf(X), m1)
        assertEquals(1978L, e.nextAt())
        assertTrue(e.due(1978))
        assertEquals(HoldRelease.bitOf(X) or HoldRelease.bitOf(Y), e.forcedMask)
    }

    // 10
    @Test fun triggersAndOtherBitsAreNeverTouched() {
        val e = engine()
        e.edge(X, true, 1000); e.keep("PAD3", 5, 1, 3, 1100)               // LT + X
        e.due(1678)
        val all = 0xFFFF
        val out = HoldRelease.outButtons(all, e.forcedMask)
        assertEquals("only X's bit (2) goes up: L2 / R2 (bits 10 / 11), LB / RB, Select, Start, the sticks, B, A, Y stay", all and (1 shl 2).inv(), out)
        assertTrue("bit 10 (L2)", out and (1 shl 10) != 0); assertTrue("bit 11 (R2)", out and (1 shl 11) != 0)
        for (i in 0..3) assertTrue("the D-pad untouched", HoldRelease.outDpad(i, true, e.forcedMask))
        // the D-pad's own mask bits never land on the buttons' 4..7 (LB / RB / Select / Start)
        val d = engine()
        d.edge(UP, true, 1000); d.keep("PADDUP", 2, 1, 3, 1100); d.due(1678)
        assertEquals("the buttons untouched by a forced D-pad up", all, HoldRelease.outButtons(all, d.forcedMask))
        assertFalse(HoldRelease.outDpad(0, true, d.forcedMask)); assertTrue(HoldRelease.outDpad(1, true, d.forcedMask))
        // LT let go before F (only LT's edge, nothing of X's): X still fires on its keep word's layer
        val f = engine()
        val fired = ArrayList<Pair<Int, Int>>()
        f.onFire = { p, l -> fired += p to l }
        f.edge(X, true, 1000); f.keep("PAD3", 5, 1, 3, 1100)
        assertTrue(f.due(1678)); assertEquals(listOf(5 to 1), fired)
        // the launcher's own copy (WinHandler.holdOut): no 0x3FF truncation, only A / X / Y of the buttons
        val w = java.io.File(listOf(java.io.File("../app"), java.io.File("app")).first { it.isDirectory },
            "src/main/java/com/winlator/winhandler/WinHandler.java").readText()
        assertTrue(w.contains("o.buttons = (short) (s.buttons & ~(m & 0x0D));") && !w.contains("& 0x3FF"))
    }

    // 11
    @Test fun theFirePointFollowsTheGamesFramesAndTheClocksNeed() {
        assertEquals(461L, HoldRelease.fireMs(2, 17)); assertEquals(678L, HoldRelease.fireMs(3, 17)); assertEquals(895L, HoldRelease.fireMs(4, 17))
        assertEquals(710L, HoldRelease.fireMs(3, 25)); assertEquals(980L, HoldRelease.fireMs(4, 34))
        assertEquals("unknown: 25", HoldRelease.fireMs(3, 25), HoldRelease.fireMs(3, null))
        // the clock's need (cn 4), whatever the app's setting says
        val e = engine(cn = 4, ft = 17)
        e.edge(X, true, 1000); e.keep("PAD3", 5, 0, 4, 1100)
        assertEquals(1000 + 895L, e.nextAt())
        // a cn that changes while the press is held: no fire (the release decides)
        val c = engine()
        c.edge(X, true, 1000); c.keep("PAD3", 5, 0, 3, 1100)
        c.cn = 2
        assertEquals(HoldRelease.NEVER, c.nextAt()); assertFalse(c.due(1678)); assertTrue(xDown(c))
        // a keep word whose need differs from the press's: no fire
        val k = engine()
        k.edge(X, true, 1000)
        assertFalse(k.keep("PAD3", 5, 0, 4, 1100))
        // no cn (an add-on before m199): never early
        val o = engine(cn = null)
        o.edge(X, true, 1000)
        assertFalse(o.keep("PAD3", 5, 0, null, 1100)); assertFalse(o.due(5000)); assertTrue(xDown(o))
        // the ft frozen at the press
        val f = engine(ft = 17)
        f.edge(X, true, 1000); f.ft = 50; f.keep("PAD3", 5, 0, 3, 1100)
        assertEquals(1678L, f.nextAt())
    }

    // 12
    @Test fun pauseResumeAndDisconnect() {
        val e = engine()
        e.edge(X, true, 1000); e.keep("PAD3", 5, 0, 3, 1100)
        e.pause()
        assertEquals("pause cancels a pending fire", HoldRelease.NEVER, e.nextAt())
        e.resume { false }
        val f = engine()
        f.edge(X, true, 1000); f.keep("PAD3", 5, 0, 3, 1100); f.due(1678)
        f.pause(); f.resume { false }
        assertEquals("resume: a forced control that reads up is let go", 0, f.forcedMask)
        val g = engine()
        g.edge(X, true, 1000); g.keep("PAD3", 5, 0, 3, 1100); g.due(1678)
        g.pause(); g.resume { true }
        assertEquals("still held: stays up for the game", HoldRelease.bitOf(X), g.forcedMask)
        g.edge(X, true, 3000)                                           // a down on a FORCED control: an up we never saw
        assertEquals("passes as a new press", 0, g.forcedMask); assertEquals(HoldRelease.HELD, g.state(X))
        g.due(3000); g.reset()
        assertEquals(0, g.forcedMask); assertEquals(HoldRelease.IDLE, g.state(X)); assertEquals(HoldRelease.NEVER, g.nextAt())
        val h = engine()
        h.edge(X, true, 1000); h.keep("PAD3", 5, 0, 3, 1100); h.cancelPending()
        assertEquals("a controller change: pending fires off", HoldRelease.NEVER, h.nextAt())
    }

    // 13 (HoldCast's half: the words, the fire's picture, the SHORT correction)
    @Test fun aShortFireRaisesTheWait() {
        var now = 10_000L
        HoldCast.clock = { now }; HoldCast.bandClock = { now }
        HoldCast.gates = { true }
        val lines = ArrayList<String>()
        HoldCast.log = { lines += it }
        HoldCast.later = { _, _ -> }
        var scheduled = HoldRelease.NEVER
        HoldCast.schedule = { scheduled = it }
        var resent = 0
        HoldCast.setResend { resent++ }
        HoldCast.padReset(true)
        HoldCast.onMap(JSONObject().put("type", "holdmap").put("on", true).put("b", "bare:5").put("why", "on").put("cn", 3).put("ft", 17))
        HoldCast.watch.press(5, 0, now)
        HoldCast.padIn(1 shl 2, false, false, false, false, now)                       // X down, as the launcher writes it
        now += 150
        HoldCast.onFacecast(JSONObject().put("type", "facecast").put("pos", 5).put("hk", "keep").put("kind", "holdkeep").put("lay", "bare")
            .put("key", "PAD3").put("need", 3))
        assertEquals("the keep word schedules the fire", 10_678L, scheduled)
        now = 10_678L; HoldCast.fireNow(now)
        assertEquals("fired: the state sent again", 1, resent)
        assertEquals("the takeover starts at the fire", 5, HoldCast.takeover.pos)
        assertEquals("the ring hands over", -1f, HoldCast.watch.charge(5, now + 10), 0f)
        // the add-on answered with the TAP (the up beat the 3rd look): back at once, later fires wait 34 ms more
        now += 120
        HoldCast.onFacecast(JSONObject().put("type", "facecast").put("pos", 5).put("hk", "rel").put("kind", "native").put("down", true)
            .put("hn", 2).put("need", 3))
        assertEquals(0f, HoldCast.takeover.alpha(now + HoldCast.EASE_MS), 0f)
        assertEquals(34L, HoldCast.release.bump)
        assertTrue(lines.any { it.startsWith("fire SHORT hn=2 need=3 ft=17") })
        repeat(5) { HoldCast.release.short() }
        assertEquals("capped at +102", 102L, HoldCast.release.bump)
        // a fire the add-on answered with its hold: one "fire ok" line, the takeover kept
        HoldCast.release.reset()
        HoldCast.padIn(0, false, false, false, false, now); now += 500
        HoldCast.padIn(1 shl 2, false, false, false, false, now)
        HoldCast.onFacecast(JSONObject().put("type", "facecast").put("pos", 5).put("hk", "keep").put("key", "PAD3").put("need", 3))
        now += 678; HoldCast.fireNow(now)
        now += 100
        HoldCast.onFacecast(JSONObject().put("type", "facecast").put("pos", 5).put("hk", "rel").put("kind", "slothold").put("down", true)
            .put("hn", 3).put("need", 3).put("sl", 0.04))
        assertTrue(lines.any { it == "fire ok n=3 ft=17 slack=40ms" })
        assertEquals(5, HoldCast.takeover.pos)
        // the physical release afterwards: no second takeover, nothing new for the game
        val start = HoldCast.takeover.startedAt
        HoldCast.padIn(0, false, false, false, false, now + 200)
        assertFalse(HoldCast.watch.release(5, now + 200))
        assertEquals(start, HoldCast.takeover.startedAt)
        assertEquals(0, HoldCast.forcedMask())
        // a fire with no answer: "fire unanswered" once
        HoldCast.padIn(1 shl 2, false, false, false, false, now + 400)
        HoldCast.onFacecast(JSONObject().put("type", "facecast").put("pos", 5).put("hk", "keep").put("key", "PAD3").put("need", 3))
        now += 400 + 678; HoldCast.fireNow(now)
        now += HoldCast.CONFIRM_MS + 10
        HoldCast.onFacecast(JSONObject().put("type", "facecast").put("pos", 6).put("kind", "native").put("down", true))
        assertEquals(1, lines.count { it.startsWith("fire unanswered pos=5") })
        HoldCast.padIn(0, false, false, false, false, now)
        HoldCast.setResend(null); HoldCast.log = { }
    }
}
