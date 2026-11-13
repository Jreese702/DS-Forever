package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE CLOCK BAND (feat/clock-extras-v2; the user, 2026-11-13: "lets center them where the ZR and LR appear (fade them out when holding ZL
 * and ZR) and make them space out evenly based on what else is turned on. the gold should be the total gold (including silver and copper)
 * when the hearthstone is ready it should have a subtle glow there should also be an ammo option."), off-device: the spot on the real
 * slot table, the even row, the trigger fade, the purse, the glow, the ammo.
 */
class ClockBandTest {
    /** Home's DIAMONDS band at rest on the Thor: 1240 x 218, the 18 px cast-bar strip */
    private val spot = CastBarPlace.clockSpot(1240f, 218f, CastBarPlace.REST_STRIP)

    private fun slotBox(pos: Int): FloatArray {
        val b = FloatArray(3)
        CastBarPlace.slot(pos, true, 1f, (218f - CastBarPlace.REST_STRIP) / CastBarPlace.TAKE_H, 0f, 0f, 1240f, b)
        val r = b[2] / 2f
        return floatArrayOf(b[0] - r, b[1] - r, b[0] + r, b[1] + r)
    }

    @Test fun theSpotIsWhereTheTriggersJoinAndOnNoButton() {
        val (l, t, r, b) = spot.toList()
        val cx = spot[4]; val joinY = spot[5]
        assertEquals(620f, cx, 0.01f)                                         // the join's centre (w / 2)
        assertEquals(218f - CastBarPlace.REST_STRIP - CastBarPlace.JOIN_BOTTOM, joinY, 0.01f)
        assertTrue(cx - l > 200f && r - cx > 200f)                            // room both sides of the join (the nearer side sets the row's)
        // the joined ZL + ZR chips (w/2 -+ 64, 84 wide) lie inside the spot's width, and the spot overlaps their row
        assertTrue(l <= 620f - 64f - 42f && r >= 620f + 64f + 42f)
        assertTrue(t < joinY + 28f && b > joinY - 28f)
        assertTrue(b <= 218f - CastBarPlace.REST_STRIP)                       // above the cast bar's strip
        assertTrue(b - t >= ClockBand.CELL_H)                                 // a full-size row fits
        for (p in 0 until 8) {
            val e = slotBox(p)
            val overlap = e[0] < r && e[2] > l && e[1] < b && e[3] > t
            assertFalse("slot $p under the band", overlap)
        }
    }

    @Test fun aNarrowRowSitsOnTheJoinAWideOneUnderTheButtons() {
        val h = ClockBand.CELL_H
        // the clock alone (~104 wide) passes between the D-pad's Right and the faces' Y: centred on the join itself
        val one = ClockBand.place(floatArrayOf(104f), spot[0], spot[2], spot[4])
        val t1 = ClockBand.rowTop(spot, one.lefts[0], one.lefts[0] + 104f, h)
        assertEquals(spot[5] - h / 2f, t1, 0.01f)
        // all six: as low as the spot's middle at most, and on no button at any count
        val all = floatArrayOf(46f, 46f, 104f, 110f, 28f, 40f)
        for (n in 1..6) {
            val w = all.copyOfRange(0, n)
            val pl = ClockBand.place(w, spot[0], spot[2], spot[4])
            val x0 = pl.lefts[0]; val x1 = pl.lefts[n - 1] + w[n - 1] * pl.scale
            val t = ClockBand.rowTop(spot, x0, x1, h * pl.scale)
            assertTrue(t >= spot[5] - h / 2f - 0.01f && t <= (spot[1] + spot[3]) / 2f - h * pl.scale / 2f + 0.01f)
            assertTrue(t + h * pl.scale <= 218f - CastBarPlace.REST_STRIP)
            for (p in 0 until 8) {
                val e = slotBox(p)
                assertFalse("n=$n slot $p under the row", e[0] < x1 && e[2] > x0 && e[1] < t + h * pl.scale && e[3] > t)
            }
        }
    }

    @Test fun itemsAreSpacedEvenlyAndCentredForEveryCount() {
        val all = floatArrayOf(46f, 46f, 104f, 110f, 28f, 40f)
        for (n in 1..6) {
            val w = all.copyOfRange(0, n)
            val pl = ClockBand.place(w, spot[0], spot[2], spot[4])
            assertEquals(1f, pl.scale, 0f)
            val first = pl.lefts[0]; val last = pl.lefts[n - 1] + w[n - 1]
            assertEquals("n=$n centred", spot[4], (first + last) / 2f, 0.01f)
            for (i in 1 until n) assertEquals("n=$n gap $i", pl.gap, pl.lefts[i] - (pl.lefts[i - 1] + w[i - 1]), 0.01f)
            assertTrue(pl.gap <= ClockBand.GAP_MAX && (n == 1 || pl.gap >= ClockBand.GAP_MIN))
            assertTrue(first >= spot[0] - 0.01f && last <= spot[2] + 0.01f)
        }
        // fewer items stand closer to the middle; never wider apart than GAP_MAX
        val two = ClockBand.place(floatArrayOf(46f, 104f), spot[0], spot[2], spot[4])
        assertEquals(ClockBand.GAP_MAX, two.gap, 0.01f)
    }

    @Test fun aRowTooWideScalesDownToFitTheSpot() {
        val w = FloatArray(6) { 120f }
        val pl = ClockBand.place(w, spot[0], spot[2], spot[4])
        assertTrue(pl.scale < 1f)
        val half = minOf(spot[4] - spot[0], spot[2] - spot[4])                // centred on the join: the nearer edge
        assertEquals(spot[4] - half, pl.lefts[0], 0.5f)
        assertEquals(spot[4] + half, pl.lefts[5] + 120f * pl.scale, 0.5f)
        assertEquals(ClockBand.GAP_MIN * pl.scale, pl.gap, 0.01f)
    }

    @Test fun theBandFadesOutWhileATriggerIsHeldAndBackOnRelease() {
        val ms = 120f
        assertEquals(1f, ClockBand.fade(1f, false, 0L, ms), 0f)
        assertEquals(0.5f, ClockBand.fade(1f, true, 60L, ms), 0.001f)
        assertEquals(0f, ClockBand.fade(1f, true, 120L, ms), 0f)
        assertEquals(0f, ClockBand.fade(1f, true, 5_000L, ms), 0f)
        // let go half-way: from where it was, back up
        assertEquals(0.5f, ClockBand.fade(0.5f, false, 0L, ms), 0.001f)
        assertEquals(1f, ClockBand.fade(0.5f, false, 120L, ms), 0f)
    }

    @Test fun thePurseIsGoldSilverAndCopperWithoutLeadingZeros() {
        assertEquals(listOf(123L to 0, 45L to 1, 67L to 2), ClockBand.coins(1_234_567L))
        assertEquals(listOf(34L to 1, 56L to 2), ClockBand.coins(3_456L))
        assertEquals(listOf(9L to 2), ClockBand.coins(9L))
        assertEquals(listOf(0L to 2), ClockBand.coins(0L))
        assertEquals(listOf(12L to 0, 0L to 1, 5L to 2), ClockBand.coins(120_005L))   // a zero after the first unit stays
        assertEquals(listOf(1L to 1, 0L to 2), ClockBand.coins(100L))
    }

    @Test fun theHearthstoneGlowsOnlyWhenReady() {
        assertTrue(ClockBand.hearthGlow(true, 0.0, false))
        assertFalse(ClockBand.hearthGlow(true, 754.0, false))                 // cooling
        assertFalse(ClockBand.hearthGlow(true, 0.0, true))                    // held dim / length unknown
        assertFalse(ClockBand.hearthGlow(null, 0.0, false))                   // not said yet
        assertFalse(ClockBand.hearthGlow(false, 0.0, false))                  // not in the bags
    }

    @Test fun ammoShowsOnlyWithAmmoInTheSlot() {
        assertNull(ClockBand.ammo(null))
        assertNull(ClockBand.ammo(JSONObject("""{"slots":[]}""")))            // no ammo slot item (a caster, a warrior with none)
        assertNull(ClockBand.ammo(JSONObject("""{"ammo":{"id":2512,"icon":132382,"count":0}}""")))
        val a = ClockBand.ammo(JSONObject("""{"ammo":{"id":2512,"icon":132382,"count":1843},"ammoNeeded":true}"""))
        assertNotNull(a)
        assertEquals(132382, a!!.icon); assertEquals(1843, a.count)
        assertEquals("1843", ClockBand.count(1843)); assertEquals("12k", ClockBand.count(12_400))
    }

    @Test fun theOrderKeepsTheClockInTheMiddle() {
        val k = ClockBand.Kind.values().toList()
        assertEquals(k, ClockBand.kinds(true, true, true, true, true))
        assertEquals(listOf(ClockBand.Kind.CLOCK), ClockBand.kinds(false, false, false, false, false))
        assertEquals(listOf(ClockBand.Kind.HEARTH, ClockBand.Kind.CLOCK, ClockBand.Kind.AMMO), ClockBand.kinds(true, false, false, false, true))
    }
}
