package com.jrees.wowcompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** b-cast-bar-size (the user: "It cannot overlap with the buttons at all"): the cast bar and its name plate never cover a button */
class CastBarPlaceTest {
    private fun hit(a: FloatArray, b: FloatArray) = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]
    private fun slots(diamonds: Boolean, t: Float, sx: Float, yo: Float, w: Float): List<FloatArray> = (0 until 8).map { pos ->
        val o = FloatArray(3); CastBarPlace.slot(pos, diamonds, t, sx, 0f, yo, w, o)
        floatArrayOf(o[0] - o[2] / 2f, o[1] - o[2] / 2f, o[0] + o[2] / 2f, o[1] + o[2] / 2f)
    }
    /** the LB / RB plates in the DIAMONDS band (CastView.drawBand: 124 x 98.6 frame px in the top corners, at width scale) */
    private fun plates(w: Float): List<FloatArray> {
        val ps = w / 1240f; val pw = 124f * ps; val ph = 124f * 31f / 39f * ps
        val y0 = (16f - CastBarPlace.TAKE_TOP) * ps
        return listOf(floatArrayOf(14f * ps, y0, 14f * ps + pw, y0 + ph), floatArrayOf(w - 14f * ps - pw, y0, w - 14f * ps, y0 + ph))
    }
    /** the bar with its frame (one unit beyond it) and the widest name plate */
    private fun barAndPlate(top: Boolean, w: Float, h: Float): List<FloatArray> {
        val b = CastBarPlace.bar(top, w, h)
        val u = (b[3] - b[1]) / 13f
        val out = mutableListOf(floatArrayOf(b[0] - u, b[1] - u, b[2] + u, b[3] + u))
        if (top) {
            val s = w / 1240f
            val p = CastBarPlace.plate(true, b[2] - b[0], b[3], 10_000f, s)           // the widest plate
            out += floatArrayOf(p[0] + b[0], p[1], p[2] + b[0], p[3])
        }
        return out
    }

    @Test fun inAFightNothingTouchesAButtonOrAPlate() {
        for (w in listOf(1240f, 1080f)) {
            val s = w / 1240f
            val h = (380f + CastBarPlace.TOP_STRIP) * s                     // HomeView: COMBAT_BARS + the top strip
            val top = CastBarPlace.TOP_STRIP * s
            val sx = (h - top) / CastBarPlace.TAKE_H
            val buttons = slots(true, 1f, sx, top, w) + plates(w)
            for (r in barAndPlate(true, w, h)) for (b in buttons) assertFalse("width $w: ${r.toList()} over ${b.toList()}", hit(r, b))
        }
    }

    @Test fun atRestInDiamondsTheLineSitsUnderTheButtons() {
        val w = 1240f; val h = 200f + CastBarPlace.REST_STRIP
        val sx = (h - CastBarPlace.REST_STRIP) / CastBarPlace.TAKE_H
        for (r in barAndPlate(false, w, h)) for (b in slots(true, 1f, sx, 0f, w)) assertFalse("${b.toList()}", hit(r, b))
    }

    @Test fun atRestInTheRowTheLineSitsUnderTheButtons() {
        val w = 1240f; val h = 112f + CastBarPlace.REST_STRIP
        for (r in barAndPlate(false, w, h)) for (b in slots(false, 0f, 1f, 0f, w)) assertFalse("${b.toList()}", hit(r, b))
    }

    @Test fun theBarIsTight() {
        // the user: "There is waaay to much blank space": a few px between the bar and the nearest button, at most
        val w = 1240f; val h = 380f + CastBarPlace.TOP_STRIP; val top = CastBarPlace.TOP_STRIP
        val sx = (h - top) / CastBarPlace.TAKE_H
        val nearest = slots(true, 1f, sx, top, w).minOf { it[1] }
        val gap = nearest - CastBarPlace.bar(true, w, h)[3]
        assertTrue("gap $gap", gap in 0f..8f)
    }

    @Test fun theTopBarIsTwiceTheRestLine() {
        val f = CastBarPlace.bar(true, 1240f, 400f); val r = CastBarPlace.bar(false, 1240f, 130f)
        assertTrue(f[3] - f[1] == 2f * (r[3] - r[1]))
    }
}
