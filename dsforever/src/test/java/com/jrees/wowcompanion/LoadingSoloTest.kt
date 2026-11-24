package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * THE BOTTOM SCREEN ALONE, AND PAINTED STAYS (the user, 2026-11-24: "Once it transitions to one screen I want the swirl on the bottom
 * screen to be smaller to fit on that screen, I also want to try keeping the swirls painted backgrounds instead of fading them to
 * black"): the passes made as they start ([LoadingMath.Schedule]) are exactly the old ones while both screens show; after the top
 * screen hands over, new passes are smaller and on the bottom alone, the running ones end as they began, and the bottom is never black
 * longer than ~50 ms through the change ("at no point do I want more than like .050 MS with a black screen"); a top screen that comes
 * back is never black either. Painted stays: the light never goes once lit, and is settled (constant) after [LoadingMath.Pass.settled].
 */
class LoadingSoloTest {
    companion object {
        lateinit var maps: LoadingMath.Maps

        @BeforeClass @JvmStatic fun build() {
            val f = listOf("../companion/app/src/main/assets/harvest/orig/home_swirl.png", "companion/app/src/main/assets/harvest/orig/home_swirl.png")
                .map(::File).first { it.isFile }
            val (n, rgb) = LoadingMathTest.png(f.readBytes())
            val lum = FloatArray(n * n) { maxOf(rgb[it * 3], rgb[it * 3 + 1], rgb[it * 3 + 2]) / 255f }
            maps = LoadingMath.maps(lum, n)
        }
    }

    private val tmp = FloatArray(3)

    /** a screen shows something of [ps] at [t]: a lit place of a picture, or the spark on it ([reg] for each pass's canvas) */
    private fun showing(top: Boolean, ps: List<LoadingMath.Pass>, t: Float, stays: Boolean = false): Boolean {
        for (p in ps) {
            val r = LoadingMath.region(top, p) ?: continue
            val q = LoadingMath.progress(p, t)
            if (q in 0f..1f) {
                LoadingMath.head(maps, p, q, tmp)
                if (tmp[0] in r.left..r.right && tmp[1] in r.top..r.bottom) return true
            }
            for ((x, y) in LoadingMathTest.grid(r, 60f)) if (LoadingMath.lit(maps, p, t, x, y, tmp, stays) > 0.1f) return true
        }
        return false
    }

    /** the most frames in a row [top] (or the bottom) shows nothing, over [from]..[to] s at 30 fps; [solo] at each time */
    private fun worstBlack(s: LoadingMath.Schedule, top: Boolean, from: Float, to: Float, solo: (Float) -> Boolean,
                           restartAt: Float? = null): Pair<Int, Float> {
        var run = 0; var worst = 0; var at = 0f
        var f = (from * 30).toInt()
        var restarted = false
        while (f < to * 30) {
            val t = f / 30f
            if (restartAt != null && !restarted && t >= restartAt) { s.restart(t); restarted = true }
            val ps = s.at(t, 8, solo(t), stays = false)
            val count = restartAt == null || t >= restartAt
            if (showing(top, ps, t)) run = 0 else if (count) { run++; if (run > worst) { worst = run; at = t } }
            f++
        }
        return worst to at
    }

    @Test fun withBothScreensTheScheduleIsTheOldFormula() {
        val s = LoadingMath.Schedule(9L, 4)
        var t = 0f
        while (t < 40f) {
            val a = s.at(t, 8, solo = false, stays = false)
            val b = LoadingMath.passes(9L, t, 8, 4)
            assertEquals("t $t", b, a)
            t += 0.37f
        }
    }

    @Test fun theBottomAloneIsNeverBlackLongerThan50ms() {
        for (seed in listOf(1L, 2L, 3L, 4L)) {
            val (w, at) = worstBlack(LoadingMath.Schedule(seed, 0), top = false, from = 0f, to = 40f, solo = { true })
            assertTrue("seed $seed bottom alone: $w black frames in a row (at $at s)", w <= 1)
        }
    }

    @Test fun theHandOverToTheBottomAloneHasNoBlackAndNoJump() {
        for (seed in listOf(1L, 2L, 3L, 5L, 8L)) for (switch in listOf(3.1f, 7.7f, 12.2f)) {
            val s = LoadingMath.Schedule(seed, 0)
            val before = s.at(switch - 0.01f, 8, solo = false, stays = false)
            val (w, at) = worstBlack(s, top = false, from = switch - 1f, to = switch + 30f, solo = { it >= switch })
            assertTrue("seed $seed switch $switch: bottom $w black frames in a row (at $at s)", w <= 1)
            // the passes running at the hand-over end as they began (both screens' size and place): no jump
            val s2 = LoadingMath.Schedule(seed, 0)
            s2.at(switch - 0.01f, 8, solo = false, stays = false)
            val after = s2.at(switch + 0.5f, 8, solo = true, stays = false)
            for (p in before) after.firstOrNull { it.k == p.k }?.let { assertEquals(p, it) }
            // every later pass is on the bottom alone, smaller
            val late = s2.at(switch + LoadingMath.EVERY * 2.1f, 8, solo = true, stays = false).filter { it.start >= switch }
            assertTrue(late.isNotEmpty())
            for (p in late) { assertTrue(p.solo); assertEquals(LoadingMath.SOLO_SCALE, p.scale, 0f) }
        }
    }

    @Test fun aTopScreenThatComesBackIsNeverBlack() {
        for (seed in listOf(1L, 2L, 3L)) for (back in listOf(9.3f, 15.0f, 21.8f)) {
            val s = LoadingMath.Schedule(seed, 0)
            // the game on the top from 2 s: solo passes; at [back] the launcher's screen is on the top again
            val (w, at) = worstBlack(s, top = true, from = 0f, to = back + 25f, solo = { it >= 2f && it < back }, restartAt = back)
            assertTrue("seed $seed back at $back: top $w black frames in a row (at $at s)", w <= 1)
        }
    }

    /** the share of [r] lit by [p] at its peak */
    private fun peak(p: LoadingMath.Pass, r: LoadingMath.Region): Float {
        var best = 0f; var t = p.start
        while (t < p.end()) {
            var lit = 0; var all = 0
            for ((x, y) in LoadingMathTest.grid(r)) { if (LoadingMath.lit(maps, p, t, x, y, tmp) > 0.35f) lit++; all++ }
            best = maxOf(best, lit.toFloat() / all); t += 0.5f
        }
        return best
    }

    @Test fun theSmallerSwirlFillsTheBottomAsTheBigOneFillsBoth() {
        val solo = (0 until 8).map { peak(LoadingMath.pass(7L, it, 8, 0, it * LoadingMath.EVERY, true), LoadingMath.SOLO) }
        val big = (0 until 8).map { k -> val p = LoadingMath.pass(7L, k, 8, 0); (peak(p, LoadingMath.TOP) + peak(p, LoadingMath.BOTTOM)) / 2f }
        println("peak lit share: bottom alone ${solo.average()} (each $solo), both screens ${big.average()}")
        assertTrue("the bottom alone is covered (${solo.average()})", solo.average() >= 0.7)
        // smaller: the spark's whole path fits the bottom screen far better than the big swirl's does
        fun inside(p: LoadingMath.Pass, r: LoadingMath.Region): Float {
            var n = 0
            for (i in 0..50) { LoadingMath.head(maps, p, i / 50f, tmp); if (tmp[0] in r.left..r.right && tmp[1] in r.top..r.bottom) n++ }
            return n / 51f
        }
        val soloIn = (0 until 8).map { inside(LoadingMath.pass(7L, it, 8, 0, 0f, true), LoadingMath.SOLO) }.average()
        val bigIn = (0 until 8).map { inside(LoadingMath.pass(7L, it, 8, 0), LoadingMath.BOTTOM) }.average()
        println("the spark's path on the bottom screen: alone $soloIn, both screens' $bigIn")
        assertTrue("fits the bottom ($soloIn vs $bigIn)", soloIn > bigIn + 0.2)
    }

    @Test fun paintedStaysNeverGoesDarkAndSettles() {
        assertEquals(1f, LoadingMath.life(LoadingMath.HOLD + LoadingMath.FADE + 5f, stays = true), 0f)
        assertEquals(0f, LoadingMath.life(LoadingMath.HOLD + LoadingMath.FADE + 5f, stays = false), 0f)
        val p = LoadingMath.pass(3L, 1, 8, 0)
        val pts = LoadingMathTest.grid(LoadingMath.BOTTOM, 80f) + LoadingMathTest.grid(LoadingMath.TOP, 80f)
        // once lit, never less lit
        val last = FloatArray(pts.size)
        var t = p.start
        while (t < p.settled() + 3f) {
            for ((i, xy) in pts.withIndex()) {
                val v = LoadingMath.lit(maps, p, t, xy.first, xy.second, tmp, stays = true)
                assertTrue("never darker at $t (${xy})", v >= last[i] - 1e-4f)
                last[i] = v
            }
            t += 0.25f
        }
        // settled: the same light from then on (what the screens keep is what they drew)
        for ((x, y) in pts) {
            val a = LoadingMath.lit(maps, p, p.settled(), x, y, tmp, stays = true)
            val b = LoadingMath.lit(maps, p, p.settled() + 60f, x, y, tmp, stays = true)
            assertEquals(a, b, 1e-5f)
        }
        // the schedule keeps a stays pass until it settled (and a moment more), never past
        val s = LoadingMath.Schedule(3L, 0)
        val p1 = s.at(LoadingMath.EVERY - LoadingMath.LEAD + 0.1f, 8, solo = false, stays = true).first { it.k == 1 }
        assertTrue(s.at(p1.settled() + 0.1f, 8, solo = false, stays = true).any { it.k == 1 })
        assertFalse(s.at(p1.settled() + LoadingMath.KEEP_SETTLED + 0.1f, 8, solo = false, stays = true).any { it.k == 1 })
    }
}
