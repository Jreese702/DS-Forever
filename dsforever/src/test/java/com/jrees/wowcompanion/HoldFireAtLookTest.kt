package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE FIRE AT THE LOOK (PadMap m206; the user on test 1536: "The immediate cast after the hold is delayed and feels like shit. It should
 * just happen"; the Thor's log: "fire ok n=2 ft=134 slack=525ms" ... "n=4 ft=134 slack=875ms"). Slack = the add-on's seconds from the
 * hold clock's need-th look to the up the game read: the launcher let the press go at F, the need-th look's LATEST time, while the ring
 * was full at R, its earliest. Now the add-on says its first look (the clock's phase: the ring's full point moves there) and its need-th
 * (the fire goes at once). This simulates the game's clock for the logged frame times and every phase, and measures in game frames:
 *  - the ring's fill to the game's up (the cast), held on;
 *  - a release after the ring is full is always the hold; before it the tap, but for the game's read of an up let go within two
 *    frames and the word's flush of the need-th look (the ring at 94 % there: the hold casts, the ring fills with it).
 */
class HoldFireAtLookTest {
    /** the add-on's word reaches the app this long after the look (the log's flush: the next frame) */
    private fun wordLag(ft: Int): Long = ft.toLong() + 5L

    private class Run(val ringFull: Long, val gameUp: Long, val look: Long, val hold: Boolean)

    /**
     * one press of A at 1000 (the game reads the down in its next frame), the game's clock first looking [phase] ms after that frame
     * and every 200 ms + up to a frame after; [rel] = the player's release (Long.MAX = held on); [words] = the add-on's look words on
     */
    private fun press(n: Int, ft: Int, phase: Long, rel: Long, words: Boolean): Run {
        val e = HoldRelease().apply { cn = n; this.ft = ft; looksSaid = words }
        val w = HoldCast.Watch({ n }, { ft }, { 0L }, { e.cn != null }, { e.waitsPos(it) }, { words }).apply { map = setOf(HoldCast.key(0, 8)) }
        val down = 1000L
        e.edge(HoldRelease.A, true, down); w.press(8, 0, down)
        val keepFrame = down + ft                                       // the game's frame that reads the down: the keep
        val lookTimes = (0 until n).map { k -> keepFrame + phase + k * (200L + ft) }   // each look on the first frame past 0.2 s
        val events = sortedMapOf<Long, MutableList<() -> Unit>>()
        fun at(t: Long, f: () -> Unit) { events.getOrPut(t) { ArrayList() }.add(f) }
        at(keepFrame + wordLag(ft)) { e.keep("PAD1", 8, 0, n, keepFrame + wordLag(ft)) }
        if (words) {
            at(lookTimes[0] + wordLag(ft)) { e.look("PAD1", 8, lookTimes[0] - keepFrame, lookTimes[0] + wordLag(ft))?.let { w.look(8, it) } }
            at(lookTimes[n - 1] + wordLag(ft)) { w.ready(8, lookTimes[n - 1] + wordLag(ft)); e.ready("PAD1", 8, lookTimes[n - 1] + wordLag(ft)) }
        }
        var gameUp = -1L
        var released = false
        var t = down
        var ringFull = -1L
        while (t < down + 4000L && gameUp < 0L) {
            events[t]?.forEach { it() }
            if (t >= e.nextWorkAt()) e.due(t)
            if (ringFull < 0L && w.charge(8, t) >= 1f) ringFull = t
            if (!released && t >= rel) {
                released = true
                e.edge(HoldRelease.A, false, t); w.release(8, t)
                if (e.keptMask and 1 == 0 && e.forcedMask and 1 == 0) gameUp = t
            }
            if (gameUp < 0L && (e.forcedMask and 1 != 0 || (released && e.keptMask and 1 == 0 && e.state(HoldRelease.A) == HoldRelease.IDLE))) gameUp = t
            t++
        }
        // the game reads the up in its next frame; the router counts the looks made before that frame
        val read = gameUp + ft
        return Run(ringFull, read, lookTimes[n - 1], lookTimes.count { it < read } >= n)
    }

    @Test fun theLoggedFrameTimesFillToCastInFrames() {
        val rows = ArrayList<String>()
        for ((n, ft) in listOf(2 to 134, 2 to 50, 2 to 67, 2 to 84, 3 to 17, 3 to 134, 4 to 67, 4 to 134)) {
            var before = 0.0; var after = 0.0; var worst = 0.0; var k = 0; var lagWorst = 0.0
            for (phase in 0L..200L step 20L) {
                val b = press(n, ft, phase, Long.MAX_VALUE, words = false)
                val a = press(n, ft, phase, Long.MAX_VALUE, words = true)
                assertTrue("held on: the hold (before)", b.hold); assertTrue("held on: the hold (after) n=$n ft=$ft phase=$phase", a.hold)
                before += (b.gameUp - b.ringFull).toDouble() / ft
                val fa = (a.gameUp - a.ringFull).toDouble() / ft
                after += fa; worst = maxOf(worst, fa); k++
                lagWorst = maxOf(lagWorst, (a.gameUp - a.look).toDouble() / ft)
            }
            rows += "n=$n ft=$ft: fill to cast ${"%.1f".format(before / k)} frames before, ${"%.1f".format(after / k)} after (worst ${"%.1f".format(worst)}); the need-th look to the cast at most ${"%.1f".format(lagWorst)} frames"
            assertTrue("n=$n ft=$ft: the cast nearer the fill than before (${"%.1f".format(after / k)} vs ${"%.1f".format(before / k)})", after < before)
            assertTrue("n=$n ft=$ft: never more than 2.5 frames past the need-th look (the word's frame, the game's read)", lagWorst <= 2.5)
        }
        println(rows.joinToString("\n"))
    }

    @Test fun aReleaseBeforeTheFullRingIsTheTapAfterItTheHold() {
        val bad = ArrayList<String>()
        for ((n, ft) in listOf(2 to 134, 3 to 17, 3 to 67, 4 to 134)) for (phase in 0L..200L step 25L) {
            val held = press(n, ft, phase, Long.MAX_VALUE, words = true)
            for (rel in (1000L + 2 * ft + 10L)..(held.ringFull + 400L) step 9L) {      // (after the keep word: a release before it is HoldRingAgreesTest's)
                val r = press(n, ft, phase, rel, words = true)
                val full = r.ringFull in 0L..rel
                // the game's own read of an up let go in the frame or two before the need-th look's word: the hold, the ring at 94 %
                val edge = !full && r.hold && held.ringFull - rel <= 2 * ft + wordLag(ft)
                if (r.hold != full && !edge) bad += "n=$n ft=$ft phase=$phase rel=+${rel - 1000}: ring ${if (full) "full" else "not full"} -> ${if (r.hold) "HOLD" else "tap"}"
            }
        }
        assertTrue("${bad.size} disagreements, e.g.\n" + bad.take(6).joinToString("\n"), bad.isEmpty())
    }

    @Test fun normalCastsAtAboutHalfASecond() {
        // the need-th look: (n - 1) x 0.2 s + the clock's phase (0 - 0.2 s) after the keep at 60 fps - Normal 0.4 - 0.6 s, mean 0.5
        val ts = (0L..200L step 10L).map { p -> press(3, 17, p, Long.MAX_VALUE, words = true).gameUp - 1000L }
        val mean = ts.average()
        assertTrue("Normal about 0.5 s: mean ${mean.toLong()} ms", mean in 470.0..600.0)
        assertEquals("Normal's looks", 3, CastSettings.HoldTime.NORMAL.ticks)
    }
}
