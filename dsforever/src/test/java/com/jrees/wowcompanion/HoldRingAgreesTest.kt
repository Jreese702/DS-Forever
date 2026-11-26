package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix/hold-tweaks (1535; the user on test 1533, 2026-11-26: "The hold to cast is triggering when releasing before the hold completes"):
 * THE RING AND THE CAST AGREE. The game's router decides at the release by the hold clock's looks (PadMap's PRE: wc-hk-t >= need), and
 * the game's state driver may look in the very frame of the keep (SecureStateDriver: its throttle runs on its own, timer <= 0 then 0.2
 * again; it is never reset by a press), so the need-th look can come as early as (need - 1) x 0.2 s after the press and as late as F.
 * The rule: a release before the ring (HoldCast.Watch.charge) is full ALWAYS gives the tap, for every phase of the game's clock; once
 * the ring is full the hold casts, held on or let go. This simulates the game end to end: the launcher's engine ([HoldRelease]: what
 * the game sees, when) and the router's count of looks at the up the game sees.
 */
class HoldRingAgreesTest {
    private val n = 3
    private val ft = 17

    /** the looks the game's driver made in [k, up): the first [phase] ms after the keep, then every [step] ms */
    private fun looks(k: Long, up: Long, phase: Long, step: Long): Int {
        var c = 0
        var t = k + phase
        while (t < up) { c++; t += step }
        return c
    }

    /** one press of A at 1000 (the keep word at 1100), let go at [rel]: (the ring was full at the release, the game's up) */
    private fun press(rel: Long): Pair<Boolean, Long> {
        val e = HoldRelease().apply { cn = n; this.ft = this@HoldRingAgreesTest.ft }
        val w = HoldCast.Watch({ n }, { ft }, { 0L }, { e.cn != null }, { e.waitsPos(it) }).apply { map = setOf(HoldCast.key(0, 8)) }
        var gameUp = -1L
        e.edge(HoldRelease.A, true, 1000); w.press(8, 0, 1000)
        assertTrue(e.keep("PAD1", 8, 0, n, 1100))
        // the launcher's fires due before the release (held on)
        while (e.nextAt() <= rel) { val at = e.nextAt(); e.due(at); if (e.forcedMask and 1 != 0 && gameUp < 0) gameUp = at }
        val full = w.charge(8, rel) >= 1f
        e.edge(HoldRelease.A, false, rel); w.release(8, rel)
        if (gameUp < 0) {
            if (e.keptMask and 1 != 0) { val at = e.nextAt(); e.due(at); gameUp = at }      // held down for the game to its fire point
            else gameUp = rel
        }
        assertEquals("never left down for the game", 0, e.keptMask and 1)
        return full to gameUp
    }

    @Test fun aReleaseBeforeTheRingIsFullIsAlwaysTheTapAndAfterItAlwaysTheHold() {
        val bad = ArrayList<String>()
        for (rel in 1050L..2000L step 5L) {
            val (full, up) = press(rel)
            for (phase in 0L..(200L + ft) step 7L) for (step in listOf(200L, 200L + ft)) {
                val hold = looks(1000, up, phase, step) >= n
                if (hold != full) bad += "release at +${rel - 1000} ms: ring ${if (full) "full" else "not full"}, the game's up at " +
                    "+${up - 1000} ms, first look +$phase, every $step ms -> ${if (hold) "HOLD" else "tap"}"
            }
        }
        assertTrue("the ring and the cast disagree in ${bad.size} cases, e.g.\n" + bad.take(6).joinToString("\n"), bad.isEmpty())
    }

    @Test fun theRingIsFullAtAboutHalfTheOldHoldTime() {
        assertEquals("Normal at 60 fps: 2 x 200 - 17 - 10", 373L, HoldRelease.ringMs(3, 17))
        assertEquals("... the hold still casts at F (held on)", 678L, HoldRelease.fireMs(3, 17))
        assertEquals("Short", 173L, HoldRelease.ringMs(2, 17)); assertEquals("Long", 573L, HoldRelease.ringMs(4, 17))
        assertEquals("unknown frame: 25", 365L, HoldRelease.ringMs(3, null))
        assertEquals("the hint", "0.4 s", HoldCast.holdWord())
    }

    @Test fun aReleaseAfterTheFillIsHeldDownForTheGameToItsFirePoint() {
        val e = HoldRelease().apply { cn = 3; ft = 17 }
        val fires = ArrayList<Int>()
        e.onFire = { p, _ -> fires += p }
        val w = HoldCast.Watch({ 3 }, { 17 }, { 0L }, { true }, { e.waitsPos(it) }).apply { map = setOf(HoldCast.key(0, 8)) }
        e.edge(HoldRelease.A, true, 1000); w.press(8, 0, 1000); e.keep("PAD1", 8, 0, 3, 1100)
        assertEquals(1373L, e.ringAtOf(HoldRelease.A))
        assertTrue("the ring not full at 1372", w.charge(8, 1372) < 1f)
        assertEquals("full at R", 1f, w.charge(8, 1373), 0f)
        e.edge(HoldRelease.A, false, 1400); w.release(8, 1400)
        assertEquals("let go at 1400: still down for the game", 1, e.keptMask and 1)
        assertTrue(e.waitsPos(8))
        assertEquals("the ring stays full, charged", 1f, w.charge(8, 1500), 0f); assertTrue(w.charged(8, 1500))
        assertEquals(1678L, e.nextAt())
        assertFalse(e.due(1677)); assertTrue(e.due(1678))
        assertEquals("let go for the game at F: the hold casts", 0, e.keptMask); assertEquals(listOf(8), fires)
        w.fired(8, 1678)
        assertEquals("the takeover has it", -1f, w.charge(8, 1679), 0f)
        assertEquals("nothing held for the picture", -1, w.layerOf(8))
    }

    @Test fun aReleaseBeforeTheFillPassesAtOnce() {
        val e = HoldRelease().apply { cn = 3; ft = 17 }
        e.edge(HoldRelease.A, true, 1000); e.keep("PAD1", 8, 0, 3, 1100)
        e.edge(HoldRelease.A, false, 1372)
        assertEquals("the tap: up for the game in the same call", 0, e.keptMask); assertEquals(HoldRelease.NEVER, e.nextAt())
    }

    @Test fun aReleaseAfterTheFillBeforeTheKeepWordWaitsForIt() {
        val e = HoldRelease().apply { cn = 3; ft = 17 }
        e.edge(HoldRelease.A, true, 1000)
        e.edge(HoldRelease.A, false, 1400)
        assertEquals("no keep word yet: held down for the game", 1, e.keptMask and 1)
        assertTrue("the word comes", e.keep("PAD1", 8, 0, 3, 1450))
        assertFalse(e.due(1677)); assertTrue(e.due(1678)); assertEquals(0, e.keptMask)
        // no word at all: its up at F + the grace, no fire
        val n = HoldRelease().apply { cn = 3; ft = 17 }
        var fired = false; n.onFire = { _, _ -> fired = true }
        n.edge(HoldRelease.A, true, 1000); n.edge(HoldRelease.A, false, 1400)
        assertEquals(1678L + HoldRelease.KEEP_LATE_MS + 1, n.nextAt())
        assertTrue(n.due(n.nextAt())); assertEquals(0, n.keptMask); assertFalse(fired)
        // a place the add-on does not keep now: never held back
        val m = HoldRelease().apply { cn = 3; ft = 17; mayKeep = { false } }
        m.edge(HoldRelease.A, true, 1000); m.edge(HoldRelease.A, false, 1400)
        assertEquals(0, m.keptMask)
        // the gates closed (Party mode, the Bind box ...): never held back
        val g = HoldRelease().apply { cn = 3; ft = 17; gatesOpen = { false } }
        g.edge(HoldRelease.A, true, 1000); g.keep("PAD1", 8, 0, 3, 1100); g.edge(HoldRelease.A, false, 1400)
        assertEquals(0, g.keptMask)
    }

    @Test fun anOldAddOnKeepsTheOldRing() {
        val w = HoldCast.Watch({ 3 }, { 17 }, { 0L }, { false }, { false }).apply { map = setOf(HoldCast.key(0, 8)) }
        w.press(8, 0, 1000)
        assertEquals("no early fire: the ring fills to F (the release decides)", 678L, w.ringMs(8))
    }

    // ---------------------------------------------------------------- THE FIRE'S MARK (the user's pick "Cast at the fill")

    /** a press of A with the keep word's mk (the router saw the armed code), let go at [rel]: (ring full at the release, the game's up,
     *  that up carried A's fire code) */
    private fun marked(rel: Long, free: Boolean = true): Triple<Boolean, Long, Boolean> {
        val e = HoldRelease().apply { cn = n; this.ft = this@HoldRingAgreesTest.ft; trigFree = { free } }
        val w = HoldCast.Watch({ n }, { ft }, { 0L }, { e.cn != null }, { e.waitsPos(it) }).apply { map = setOf(HoldCast.key(0, 8)) }
        var up = -1L; var code = false
        e.edge(HoldRelease.A, true, 1000); w.press(8, 0, 1000)
        assertEquals("armed while held: code 3", 3, e.markCode(1000))
        assertTrue(e.keep("PAD1", 8, 0, n, 1100, mk = true))
        while (e.nextAt() <= rel) {
            val at = e.nextAt(); e.due(at)
            if (e.forcedMask and 1 != 0 && up < 0) { up = at; code = e.markCode(at) == 6 }
        }
        val full = w.charge(8, rel) >= 1f
        if (up < 0) code = e.markCode(rel) == 6
        e.edge(HoldRelease.A, false, rel); w.release(8, rel)
        if (up < 0) {
            if (e.keptMask and 1 != 0) { val at = e.nextAt(); e.due(at); up = at; code = e.markCode(at) == 6 } else up = rel
        }
        return Triple(full, up, code)
    }

    @Test fun aMarkedPressCastsAtTheFillAndARelaseBeforeItIsTheTap() {
        val bad = ArrayList<String>()
        for (rel in 1050L..2000L step 5L) {
            val (full, up, code) = marked(rel)
            if (full && up != 1000L + HoldRelease.ringMs(n, ft)) bad += "release +${rel - 1000}: ring full but the game's up at +${up - 1000} (not R)"
            for (phase in 0L..(200L + ft) step 7L) for (step in listOf(200L, 200L + ft)) {
                val hold = code || looks(1000, up, phase, step) >= n                 // the router: its own fire code, or its looks
                if (hold != full) bad += "release +${rel - 1000}: ring ${if (full) "full" else "not full"} -> ${if (hold) "HOLD" else "tap"}"
            }
        }
        assertTrue("${bad.size} cases, e.g. " + bad.take(6).joinToString(" | "), bad.isEmpty())
    }

    @Test fun theMarksCodes() {
        val e = HoldRelease().apply { cn = 3; ft = 17 }
        assertEquals("nothing held: no code", 0, e.markCode(0))
        e.edge(HoldRelease.X, true, 1000)
        assertEquals("X held: armed 3", 3, e.markCode(1000))
        e.keep("PAD3", 5, 0, 3, 1100, mk = true)
        assertEquals("a marked keep fires at R", 1373L, e.nextAt())
        assertTrue(e.due(1373))
        assertEquals("the fire's write: 6 + X (1)", 7, e.markCode(1373))
        assertEquals("... for MARK_MS", 7, e.markCode(1373 + HoldRelease.MARK_MS - 1))
        assertEquals("then none (X up for the game, nothing else held)", 0, e.markCode(1373 + HoldRelease.MARK_MS))
        // no mk in the keep word: F, as before
        val f = HoldRelease().apply { cn = 3; ft = 17 }
        f.edge(HoldRelease.A, true, 1000); f.keep("PAD1", 8, 0, 3, 1100)
        assertEquals(1678L, f.nextAt())
        // no free trigger at R (both held for LT + RT): no code can go - the fire waits for F (the looks decide there)
        val t = HoldRelease().apply { cn = 3; ft = 17; trigFree = { false } }
        var fires = 0; t.onFire = { _, _ -> fires++ }
        t.edge(HoldRelease.A, true, 1000); t.keep("PAD1", 8, 0, 3, 1100, mk = true)
        assertFalse(t.due(1373)); assertEquals(0, fires); assertEquals(1678L, t.nextAt())
        assertTrue(t.due(1678)); assertEquals(1, fires); assertEquals("no code at F", 0, t.markCode(1678))
        // the session's fail-safe: a marked fire the router answered with the tap - no more fires at R
        val o = HoldRelease().apply { cn = 3; ft = 17; markOff = true }
        o.edge(HoldRelease.A, true, 1000); o.keep("PAD1", 8, 0, 3, 1100, mk = true)
        assertEquals(1678L, o.nextAt())
    }
}
