package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.floor

/**
 * fix/stream-row-offset (the user, 2026-11-18, test 1494 streamed: "testing stream, ui still didnt hide"): THE STREAM'S TAPS MEASURED,
 * off-device, with the PC's own numbers - WoW 1920x1080 (borderless, maximized), game-default UI scale, the 1494 pad document (pnav row
 * 1, chq 2, uisw 50, ui 51; TapPad pad24's strip 6 px left of the grid over 56 rows). The PC's game is modelled as what test 1494's
 * landings fit: the cursor lands on a whole desktop pixel, and the game sees it at a x that pixel + b. TapPad's side is
 * tools/test_pad_cal.py.
 */
class TapCalTest {
    @Before fun clean() = TapCal.reset()
    @After fun after() = TapCal.reset()

    /** a PC: the desktop pixel a tap aimed at game y [aim] falls on (1080-line stream = the game's own px), seen by the game at a * px + b */
    private class Pc(val a: Double, val b: Double) {
        fun seen(aim: Double): Double = a * floor(aim) + b
    }

    /** test 1494's PC: inside every (a, b) its landing log allows with whole-pixel cursors (a 0.9745-0.98, b 1.31-1.89) */
    private val pc1494 = Pc(0.978, 1.5)

    private fun row(seenY: Double) = floor((seenY - 12) / 2).toInt()
    private fun top(row: Int) = 12.0 + row * 2

    private val names = mapOf(1 to "pnav", 2 to "chq", 11 to "auracancel", 12 to "gf", 17 to "qact", 24 to "idlg", 38 to "gamemenu",
        50 to "uisw", 51 to "ui")

    private fun pad(n: Int = 0, p: String? = null, ph: Int = 1080, pw: Int = 1920) = JSONObject().apply {
        put("v", "pad24"); put("x", 840); put("y", 12); put("cw", 6); put("ch", 2); put("cols", 40); put("pw", pw); put("ph", ph)
        put("rows", JSONObject().apply { for ((r, nm) in names) put(nm, r) })
        put("calib", JSONObject().apply { put("x", 828); put("y", 12); put("w", 6); put("h", 112); put("n", n); if (p != null) put("p", p) })
    }

    /** the strip measured on [pc]: the fit the app takes from its two presses */
    private fun measure(pc: Pc, key: String = "k"): TapCal.Fit {
        val s = TapCal.strip(pad())!!
        val aims = TapCal.aimsFor(s)
        return TapCal.fitOf(aims.map { it.second }, aims.map { pc.seen(it.second) }, key)!!
    }

    @Test fun test1494IsReproducedWithoutAFit() {
        // the app's aims, as it sent them in 1494 (the click log: 15.5 / 113.5 / 115.5 ...)
        assertEquals(115.5, TapCal.cellY(top(51), 2.0, null), 1e-9)
        assertEquals(15.5, TapCal.cellY(top(1), 2.0, null), 1e-9)
        // what the game made of them: ui -> uisw, pnav -> chq, the rows between right (every landing of 17:01-17:18)
        assertEquals(50, row(pc1494.seen(TapCal.cellY(top(51), 2.0, null))))
        assertEquals(2, row(pc1494.seen(TapCal.cellY(top(1), 2.0, null))))
        for (r in listOf(11, 12, 17, 24, 38, 50)) assertEquals("row $r", r, row(pc1494.seen(TapCal.cellY(top(r), 2.0, null))))
    }

    @Test fun withTheMeasuredFitEveryRowLandsOnTheTestPc() {
        val f = measure(pc1494)
        assertEquals(pc1494.a, f.a, 1e-9)
        for (r in 0 until 56) assertEquals("row $r", r, row(pc1494.seen(TapCal.cellY(top(r), 2.0, f))))
        // ui -> ui, pnav -> pnav
        assertEquals(51, row(pc1494.seen(TapCal.cellY(top(51), 2.0, f))))
        assertEquals(1, row(pc1494.seen(TapCal.cellY(top(1), 2.0, f))))
    }

    @Test fun everyRowLandsOnAnyPcTheFitCanMeasure() {
        // the whole range 1494's landings allow, the 1440p PC of 1.0.21 (cursor 101 for 103), an exact PC, a stretch and plain offsets
        val pcs = listOf(Pc(0.9745, 1.85), Pc(0.975, 1.78), Pc(0.98, 1.31), Pc(0.98, 1.35), Pc(0.977, 1.6), Pc(1440.0 / 1464.0, 1.5),
            Pc(1.0, 0.0), Pc(1.02, -1.0), Pc(1.0, 1.5), Pc(1.0, -1.5), Pc(0.95, 3.0), Pc(1.05, -4.0))
        for (pc in pcs) {
            val f = measure(pc)
            for (r in 0 until 56) assertEquals("a ${pc.a} b ${pc.b} row $r", r, row(pc.seen(TapCal.cellY(top(r), 2.0, f))))
        }
    }

    @Test fun anExactPcKeepsTodaysPoint() {
        val f = measure(Pc(1.0, 0.0))
        for (r in 0 until 56) assertEquals(TapCal.cellY(top(r), 2.0, null), TapCal.cellY(top(r), 2.0, f), 1e-9)
    }

    @Test fun aFitThatCannotBeTrustedIsRefused() {
        assertNull(TapCal.fitOf(listOf(16.5, 20.5), listOf(16.0, 20.0), "k"))            // too close together
        assertNull(TapCal.fitOf(listOf(16.5, 119.5), listOf(16.0, 60.0), "k"))           // a = 0.43
        assertNull(TapCal.fitOf(listOf(16.5, 119.5), listOf(70.0, 173.0), "k"))          // b = 53.5
        assertNotNull(TapCal.fitOf(listOf(16.5, 119.5), listOf(17.1, 117.8), "k"))
    }

    @Test fun theStripTapsAreTwoPixelCentresInsideTheStrip() {
        val s = TapCal.strip(pad())!!
        val aims = TapCal.aimsFor(s)
        assertEquals(listOf(831.5 to 16.5, 831.5 to 119.5), aims)
        for ((x, y) in aims) assertTrue(x > s.x && x < s.x + s.w && y > s.y && y < s.y + s.h)
        // the test PC sees both inside the strip too (the strip takes the presses)
        for ((_, y) in aims) assertTrue(pc1494.seen(y) > s.y && pc1494.seen(y) < s.y + s.h)
    }

    @Test fun theStreamMeasuresOnceAndAimsThroughIt() {
        TapCal.boot = 41L
        assertNull("Local: no strip taps", TapCal.wanted(pad(), streaming = false, now = 0))
        val aims = TapCal.wanted(pad(), streaming = true, now = 0)!!
        assertTrue(TapCal.busy(100))
        assertNull("one at a time", TapCal.wanted(pad(), streaming = true, now = 100))
        // the add-on's next document: the strip saw two presses, where the test PC puts them
        val seen = aims.joinToString(" ") { (x, y) -> "$x,${"%.1f".format(java.util.Locale.ROOT, pc1494.seen(y))}" }
        val note = TapCal.settle(pad(n = 2, p = seen), now = 400)
        assertNotNull(note); assertTrue(note!!, note.startsWith("tap calibration (stream): seen = y x0.97"))
        assertFalse(TapCal.busy(400))
        val f = TapCal.current(pad(n = 2, p = seen))!!
        for (r in 0 until 56) assertEquals("row $r", r, row(pc1494.seen(TapCal.cellY(top(r), 2.0, f))))
        assertNull("measured: nothing more to tap", TapCal.wanted(pad(n = 2, p = seen), streaming = true, now = 500))
        // a /reload (a new boot) or another screen is measured again
        TapCal.boot = 42L
        assertNull(TapCal.current(pad(n = 2, p = seen)))
        assertNotNull(TapCal.wanted(pad(n = 2, p = seen), streaming = true, now = 600))
    }

    @Test fun noAnswerIsTriedThreeTimesThenSaidOnce() {
        TapCal.boot = 1L
        for (i in 0 until TapCal.MAX_TRIES) {
            val t = i * 10_000L
            assertNotNull("try ${i + 1}", TapCal.wanted(pad(), streaming = true, now = t))
            val note = TapCal.settle(pad(), now = t + TapCal.WAIT_MS)
            if (i < TapCal.MAX_TRIES - 1) assertNull(note) else assertTrue(note!!.startsWith("tap calibration: no answer"))
        }
        assertNull("the taps go as before", TapCal.wanted(pad(), streaming = true, now = 100_000))
        assertNull(TapCal.current(pad()))
    }

    @Test fun aPressOnAnotherRowIsNotedOnceAndDropsTheFit() {
        TapCal.boot = 5L
        val aims = TapCal.wanted(pad(), true, 0)!!
        val seen = aims.joinToString(" ") { (x, y) -> "$x,${pc1494.seen(y)}" }
        TapCal.settle(pad(n = 2, p = seen), 300)
        assertNotNull(TapCal.current(pad(n = 2, p = seen)))
        TapCal.aimed(listOf(TapCal.Aim("ui", 34, top(51), 2.0, 115.5, 1_000)))
        val note = TapCal.landed("uisw", 34, 113.0, 1_200)
        assertEquals("tap landed off: ui:34 (rows y 114.0-116.0, aimed 115.5) pressed uisw:34 seen at y 113.0", note)
        // fix/stream-row-center: a neighbour-row miss keeps the fit (measuring again gives the same two whole pixels)...
        assertNotNull("a near miss keeps the fit", TapCal.current(pad(n = 2, p = seen)))
        // ...a press seen more than a row off drops it
        TapCal.aimed(listOf(TapCal.Aim("ui", 35, top(51), 2.0, 115.5, 1_000)))
        assertNotNull(TapCal.landed("gamemenu", 35, 89.0, 1_300))
        assertNull("the fit is dropped: measured again", TapCal.current(pad(n = 2, p = seen)))
        TapCal.aimed(listOf(TapCal.Aim("ui", 34, top(51), 2.0, 115.5, 2_000)))
        assertNull("said once per 30 s", TapCal.landed("uisw", 34, 113.0, 2_100))
        // a press where it was meant says nothing
        TapCal.aimed(listOf(TapCal.Aim("uisw", 3, top(50), 2.0, 113.5, 3_000)))
        assertNull(TapCal.landed("uisw", 3, 113.4, 3_100))
    }

    @Test fun theTapLogChecksTheRingsNewestPresses() {
        TapCal.aimed(listOf(TapCal.Aim("pnav", 37, top(1), 2.0, 15.5, 1_000), TapCal.Aim("ui", 31, top(51), 2.0, 115.5, 1_000)))
        val notes = GameUi.landedChecks("uisw:5 chq:37 uisw:31", "830,113.2 1063.5,16.1 1029.5,113.4", fresh = 2, now = 1_500)
        assertEquals(2, notes.size)
        assertTrue(notes[0], notes[0].startsWith("tap landed off: pnav:37 (rows y 14.0-16.0, aimed 15.5) pressed chq:37 seen at y 16.1"))
        assertTrue(notes[1], notes[1].startsWith("tap landed off: ui:31"))
        // an older add-on (no `at`): still checked by name
        TapCal.reset()
        TapCal.aimed(listOf(TapCal.Aim("ui", 31, top(51), 2.0, 115.5, 1_000)))
        assertEquals(1, GameUi.landedChecks("uisw:31", "", fresh = 1, now = 1_500).size)
    }

    @Test fun theAddOnsRealRowTopIsWhatTheAimUses() {
        // pad23's `off`: a row whose cells stand elsewhere is aimed where they really are (cellY over the real top)
        assertEquals(69.5, TapCal.cellY(68.0, 2.0, null), 1e-9)
    }
}
