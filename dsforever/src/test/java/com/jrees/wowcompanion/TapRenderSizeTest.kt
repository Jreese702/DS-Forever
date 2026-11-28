package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

/**
 * RENDER SIZE: THE TAP PAD'S PRECISION (the user's rulings 2026-11-27 on PR 615; 720p is the default on every device). A tap goes game
 * px -> the top screen's display px (TapShape.displayOf, the 1920x1080 panel) -> the shell line's 2 decimals (TapShape.tapLine) ->
 * a fraction of the display (InputSink.play) -> the X screen's whole pixel (XServerScreen's sink: (f x width).toInt()). At 1280x720
 * (1.5 display px a game px) and 1600x900 (1.2) every 6 x 2 pad cell's aim must still land on its own cell's own pixel, as at 1080p.
 * The pads are the add-on's own describe() (tools/test_pad_order.py's fixtures; 1600x900 is built by the add-on's own layout rule:
 * x0 = floor((pw - 40 x 6) / 2), y 12).
 */
class TapRenderSizeTest {
    @After fun clean() { InputSink.sink = null; InputSink.displayW = 0; InputSink.displayH = 0 }

    private fun fixture(name: String) = JSONObject(javaClass.classLoader!!.getResourceAsStream("pad-fixtures/$name.json")!!.bufferedReader().readText())

    /** the add-on's grid for a pw x ph screen whose rows 0 until [rows] are placed */
    private fun built(pw: Int, ph: Int, rows: Int) = JSONObject().put("pw", pw).put("ph", ph).put("cw", 6).put("ch", 2).put("cols", 40)
        .put("x", floor((pw - 240) / 2.0).toInt()).put("y", 12).put("rows", JSONObject().apply { for (r in 0 until rows) put("r$r", r) })

    /** every row of [pad] at columns 0, 1, 20 and 39: the X pixel each tap reaches is inside its own cell */
    private fun walk(pad: JSONObject) {
        val pw = pad.getInt("pw"); val ph = pad.getInt("ph")
        val cw = pad.getDouble("cw"); val ch = pad.getDouble("ch"); val x0 = pad.getDouble("x"); val y0 = pad.getDouble("y")
        val got = ArrayList<Pair<Int, Int>>()
        InputSink.sink = object : InputSink.Sink {
            override fun key(xKeycode: Int, down: Boolean) {}
            // XServerScreen's sink: the fraction onto the X screen's whole pixels
            override fun tap(fx: Float, fy: Float) { got += (fx * pw).toInt() to (fy * ph).toInt() }
        }
        InputSink.displayW = 1920; InputSink.displayH = 1080
        val rows = pad.getJSONObject("rows")
        val off = pad.optJSONObject("off")
        var n = 0
        for (name in rows.keys()) {
            val row = rows.getInt(name)
            val top = off?.takeIf { it.has(name) }?.getDouble(name) ?: (y0 + row * ch)
            val script = StringBuilder()
            val want = ArrayList<Pair<IntRange, IntRange>>()
            for (col in listOf(0, 1, 20, 39)) {
                // TapBridge.cellAim with no stream fit
                val gx = x0 + (col + 0.5) * cw + 0.5
                val gy = TapCal.cellY(top, ch, null)
                val (dx, dy) = TapShape.displayOf(gx, gy, pw.toDouble(), ph.toDouble(), 1920, 1080)
                script.append(TapShape.tapLine(dx, dy)).append('\n')
                want += ((x0 + col * cw).toInt() until (x0 + (col + 1) * cw).toInt()) to (top.toInt() until (top + ch).toInt())
            }
            got.clear()
            InputSink.play(script.toString())
            assertEquals("$name: four taps played (${pw}x$ph)", 4, got.size)
            for ((i, p) in got.withIndex()) {
                assertTrue("$name col ${listOf(0, 1, 20, 39)[i]} at ${pw}x$ph: x ${p.first} in ${want[i].first}", p.first in want[i].first)
                assertTrue("$name at ${pw}x$ph: y ${p.second} in ${want[i].second}", p.second in want[i].second)
                // the row's own pixel: a 2-px row's lower pixel (the one it shares with no other row)
                assertEquals("$name at ${pw}x$ph: the row's own pixel", top.toInt() + 1, p.second)
            }
            n++
        }
        assertTrue("rows walked at ${pw}x$ph", n > 0)
    }

    @Test fun everyCellLandsOnItsOwnPixelAt720p() {
        walk(fixture("pad_1280x720_low"))
        // 720p with the game's UI at its own scale (popupTop 126.6: all 56 rows placed, tools/test_pad_order.py case l)
        walk(built(1280, 720, 56))
    }

    @Test fun everyCellLandsOnItsOwnPixelAt900p() = walk(built(1600, 900, 56))

    @Test fun andAt1080pAsBefore() = walk(fixture("pad_1920x1080"))

    @Test fun theShapeCheckPassesForEveryRenderSize() {
        // TapBridge.gamePoint refuses a game screen of another shape than the top screen (2 %): every render size is 16:9
        for (lines in RenderSize.CHOICES) {
            val (w, h) = RenderSize.size(lines).split("x").map { it.toDouble() }
            assertTrue(RenderSize.size(lines), kotlin.math.abs(1920.0 / 1080.0 - w / h) <= 0.02)
        }
    }
}
