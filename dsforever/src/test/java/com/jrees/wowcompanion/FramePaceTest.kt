package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.PriorityQueue

/**
 * 1529 PERFORMANCE MODE (FramePace), off-device: a bottom-screen view whose onDraw asks for the next frame at every draw (the map's
 * trail, the swing, a flash) on a 60 Hz vsync, counted over simulated seconds - at rest (every vsync, as before) and moving / fighting
 * (paced). The measured numbers are printed (the build's report).
 */
class FramePaceTest {
    @After fun tearDown() = FramePace.reset()

    /** a tiny simulated main thread: vsync every [vsyncMs], posted runnables at their times, one view that draws when dirty */
    private class Sim(val vsyncMs: Double = 1000.0 / 60.0, busy: (Long) -> Boolean) {
        var now = 0.0
        var dirty = false
        var draws = 0
        val drawTimes = ArrayList<Double>()
        private var seq = 0
        private val q = PriorityQueue<Triple<Double, Int, Runnable>>(compareBy({ it.first }, { it.second }))
        val gate = FramePace.Gate({ now.toLong() }, busy, { dirty = true }, { r, ms -> q.add(Triple(now + ms, seq++, r)) }, { dirty = true })

        fun run(ms: Double, onDraw: () -> Unit) {
            val end = now + ms
            var nextVsync = Math.ceil(now / vsyncMs) * vsyncMs
            while (true) {
                val pt = q.peek()?.first
                if (pt != null && pt <= nextVsync) { if (pt > end) break; now = pt; q.poll()!!.third.run(); continue }
                if (nextVsync > end) break
                now = nextVsync
                nextVsync += vsyncMs
                if (dirty) { dirty = false; draws++; drawTimes.add(now); onDraw() }
            }
            now = end
        }
    }

    private fun framesPerSecond(busy: Boolean, vsyncHz: Double = 60.0): Int {
        val s = Sim(1000.0 / vsyncHz) { busy }
        s.dirty = true
        s.run(1000.0) { s.gate.frame() }
        return s.draws
    }

    @Test fun atRestEveryVsyncAsBefore() {
        val n = framesPerSecond(busy = false)
        println("FramePace: at rest $n frames/s")
        assertTrue("at rest: every vsync ($n)", n in 59..61)
    }

    @Test fun movingOrFightingAboutThirtyFramesASecond() {
        val rest = framesPerSecond(busy = false)
        val busy = framesPerSecond(busy = true)
        println("FramePace: moving / fighting $busy frames/s (at rest $rest): ${100 - busy * 100 / rest}% fewer bottom-screen frames")
        assertTrue("paced: 20 - 32 frames a second ($busy)", busy in 20..32)
        // a 120 Hz screen: still far under its own rate
        val b120 = framesPerSecond(busy = true, vsyncHz = 120.0)
        println("FramePace: on a 120 Hz screen $b120 frames/s paced (120 at rest)")
        assertTrue("120 Hz paced ($b120)", b120 in 20..32)
    }

    @Test fun theFramesAreEvenlySpaced() {
        val s = Sim { true }
        s.dirty = true
        s.run(1000.0) { s.gate.frame() }
        val gaps = s.drawTimes.zipWithNext { a, b -> b - a }.drop(2)
        assertTrue("every gap ~33 ms: ${gaps.distinct()}", gaps.all { it in 33.0..34.0 })
    }

    @Test fun manyRequestsBetweenTwoFramesShareOneFrame() {
        // a view that asks five times per draw (its parts each ask) and gets new documents in between: still one paced chain
        val s = Sim { true }
        s.dirty = true
        s.run(1000.0) { repeat(5) { s.gate.frame() } }
        assertTrue("coalesced (${s.draws})", s.draws in 20..33)
    }

    @Test fun aRequestAfterAQuietSpellGoesAtOnce() {
        // a pad press's first glow frame: nothing drawn for a while, then a request -> the next vsync, not a paced wait
        val s = Sim { true }
        s.run(500.0) { }
        s.gate.frame()
        assertTrue(s.dirty)
    }

    @Test fun aRequestLongAfterAPacedChainGoesAtOnceToo() {
        // a paced animation that ended (its last draw asked for nothing), then a pad press half a second later: the next vsync
        val s = Sim { true }
        s.dirty = true
        var frames = 10
        s.run(1000.0) { if (frames-- > 0) s.gate.frame() }
        assertFalse(s.dirty)
        s.gate.frame()
        assertTrue("at once, not a paced wait", s.dirty)
    }

    @Test fun aLostTickNeverStopsTheView() {
        // the posted tick dropped (a detached view's queue): the view still animates once LOST_MS has passed
        var t = 0L
        var asked = 0; var drawn = 0
        val g = FramePace.Gate({ t }, { true }, { drawn++ }, { _, _ -> asked++ }, { drawn++ })
        g.frame(); assertEquals(1, drawn)                     // at once (nothing drawn before)
        t = 5; g.frame(); assertEquals(1, asked)              // paced: a tick posted ... and lost
        t = 100; g.frame(); assertEquals("still waiting on it", 1, asked)
        t = 5 + FramePace.LOST_MS; g.frame()
        assertTrue("asked again or drawn ($asked, $drawn)", asked == 2 || drawn == 2)
    }

    @Test fun aTouchGivesFullRateForAMoment() {
        var p = true
        FramePace.probe = { p }
        assertTrue(FramePace.busy(10_000L))
        FramePace.touched(10_050L)
        assertFalse(FramePace.busy(10_100L))
        assertFalse(FramePace.busy(10_050L + FramePace.TOUCH_HOLD_MS - 1))
        assertTrue(FramePace.busy(10_050L + FramePace.TOUCH_HOLD_MS + FramePace.PROBE_MS))
        p = false
        assertFalse(FramePace.busy(10_050L + FramePace.TOUCH_HOLD_MS + 3 * FramePace.PROBE_MS))
    }

    @Test fun noProbeNeverPaced() {
        FramePace.probe = null
        assertFalse(FramePace.busy(1_000L))
        FramePace.probe = { throw IllegalStateException("not ready") }
        assertFalse(FramePace.busy(5_000L))
    }

    private val src = listOf(File("../companion/app/src/main/java/com/jrees/wowcompanion"), File("companion/app/src/main/java/com/jrees/wowcompanion"))
        .first { it.isDirectory }

    @Test fun theBottomScreensAnimatedViewsArePacedAndTheHostSaysWhen() {
        for (v in listOf("MapView.kt", "CastView.kt", "HomeView.kt", "FightCastStripView.kt")) {
            val t = File(src, v).readText()
            assertTrue(v, t.contains("private val pace: FramePace.Gate? = FramePace.gate(this) { vsyncFrame() }"))
            assertTrue(v, t.contains("override fun postInvalidateOnAnimation() { pace?.frame() ?: super.postInvalidateOnAnimation() }"))
        }
        val host = File(src, "CompanionHost.kt").readText()
        assertTrue(host.contains("FramePace.probe = { room == Room.HOME && homeView.isShown && (homeView.inFight() || lastBook?.combat == true || mapView.movingNow()) }"))
        assertTrue(host.contains("FramePace.touched(android.os.SystemClock.uptimeMillis())"))
    }
}
