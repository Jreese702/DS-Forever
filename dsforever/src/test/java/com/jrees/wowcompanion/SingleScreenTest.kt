package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SINGLE SCREEN MODE'S SHARED STATE (the user, 2026-11-25: "a single screen mode where the game shifts to the top screen when a button
 * is pushed ... The button to shift it needs to be in the pause menu below the edit mode button"): the refusals and their words in their
 * order (Swap screens, streaming, loading, a fight - builder defaults), the centred panel's box (the pick "Centred panel"), one listener
 * call per change, the problem report's line.
 */
class SingleScreenTest {
    @After fun clean() = SingleScreen.reset()

    private val ok = SingleScreen.Ask(swapInUse = false, streaming = false, loading = false, inWorld = true, fight = false)

    @Test fun nothingInTheWayAllowsTheSwitch() = assertNull(SingleScreen.refusal(ok))

    @Test fun theRefusalsInTheirOrder() {
        val all = SingleScreen.Ask(swapInUse = true, streaming = true, loading = true, inWorld = false, fight = true)
        assertEquals("Turn Swap screens Off first", SingleScreen.refusal(all))
        assertEquals("Not while streaming", SingleScreen.refusal(all.copy(swapInUse = false)))
        assertEquals("Not while loading", SingleScreen.refusal(all.copy(swapInUse = false, streaming = false)))
        assertEquals("Not while loading", SingleScreen.refusal(ok.copy(inWorld = false)))      // not in the world yet = loading
        assertEquals("Not while loading", SingleScreen.refusal(ok.copy(loading = true, fight = true)))
        assertEquals("Not in a fight", SingleScreen.refusal(ok.copy(fight = true)))
        // the review (2026-11-25): back to two screens with an activity of ours up on the other screen (a new bottom window would cover it)
        assertEquals("Close the bottom screen's window first", SingleScreen.refusal(ok.copy(bottomBusy = true)))
        assertEquals("a fight first", "Not in a fight", SingleScreen.refusal(ok.copy(bottomBusy = true, fight = true)))
    }

    /** the review (2026-11-25): the hint plate's pinned words survive a timed note over them ("Tap the game for the controller") */
    @Test fun thePlatesPinnedWordsComeBackAfterANote() {
        val p = SingleScreen.Plate()
        assertEquals(SingleScreen.TAP_FOR_KEYS, p.set(SingleScreen.TAP_FOR_KEYS, 0L))
        assertEquals("a note over it", "On the bottom screen", p.set("On the bottom screen", 2_500L))
        assertEquals("its end: the pinned words again", SingleScreen.TAP_FOR_KEYS, p.timedEnd())
        assertEquals("a timed note's own end with nothing pinned: none", null, SingleScreen.Plate().also { it.set("x", 100L) }.timedEnd())
        assertEquals("cleared while a note shows: the note keeps its time", "Controller back", p.set("Controller back", 1_200L).also { p.set(null, 0L) })
        assertEquals(null, p.timedEnd())
    }

    @Test fun everyRefusalHasSpanishAndFrench() {
        val i18n = listOf(File("../companion/app/src/main/assets/companion/i18n"), File("companion/app/src/main/assets/companion/i18n"))
            .first { it.isDirectory }
        val words = listOf(SingleScreen.SWAP_FIRST, SingleScreen.NOT_STREAMING, SingleScreen.NOT_LOADING, SingleScreen.NOT_FIGHT,
            SingleScreen.CONTROLLER_BACK, SingleScreen.TAP_FOR_KEYS, GameUi.SWITCHING, SingleScreen.BOTTOM_FIRST)
        for (l in listOf("es", "fr")) {
            val text = File(i18n, "$l.json").readText()
            val plain = JSONObject(text).getJSONObject("plain")
            for (w in words) assertTrue("$l: $w", plain.has(w))
            assertTrue("$l keeps CRLF", text.contains("\r\n") && !text.replace("\r\n", "").contains("\n"))
        }
        val es = JSONObject(File(i18n, "es.json").readText()).getJSONObject("plain")
        assertEquals("Desactiva primero Intercambiar pantallas", es.getString(SingleScreen.SWAP_FIRST))
        assertEquals("Mando recuperado", es.getString(SingleScreen.CONTROLLER_BACK))
        val fr = JSONObject(File(i18n, "fr.json").readText()).getJSONObject("plain")
        assertEquals("Pas pendant le chargement", fr.getString(SingleScreen.NOT_LOADING))
        assertEquals("Touchez le jeu pour reprendre la manette", fr.getString(SingleScreen.TAP_FOR_KEYS))
    }

    @Test fun theCentredPanel() {
        // the Thor's top screen: the bottom screen's page 1:1, 340 px of game each side
        assertArrayEquals(intArrayOf(340, 0, 1580, 1080), SingleScreen.panelBox(1920, 1080))
        // a 720p screen: 1240:1080 as large as fits (827 x 720), centred
        val b = SingleScreen.panelBox(1280, 720)
        assertEquals(720, b[3] - b[1]); assertEquals(827, b[2] - b[0]); assertEquals((1280 - 827) / 2, b[0]); assertEquals(0, b[1])
        // a 20:9 phone in landscape: full height, wide margins
        assertArrayEquals(intArrayOf(580, 0, 1820, 1080), SingleScreen.panelBox(2400, 1080))
        // a phone held upright: the full width, centred vertically, the shape kept
        val p = SingleScreen.panelBox(1080, 2400)
        assertEquals(1080, p[2] - p[0]); assertEquals(Math.round(1080 * 1080f / 1240), p[3] - p[1]); assertEquals((2400 - (p[3] - p[1])) / 2, p[1])
        // the bottom screen itself: the whole of it
        assertArrayEquals(intArrayOf(0, 0, 1240, 1080), SingleScreen.panelBox(1240, 1080))
    }

    @Test fun eachChangeIsHeardOnce() {
        var n = 0
        val l: () -> Unit = { n++ }
        SingleScreen.onChange(l); SingleScreen.onChange(l)                 // the same listener twice: kept once
        SingleScreen.set(true, SingleScreen.Why.USER, at = 100L)
        SingleScreen.set(true, SingleScreen.Why.USER, at = 200L)          // no change: not heard
        assertEquals(1, n)
        assertTrue(SingleScreen.inUse); assertEquals(SingleScreen.Why.USER, SingleScreen.why)
        SingleScreen.set(true, SingleScreen.Why.DEVICE, at = 300L)        // why changed: heard
        assertEquals(2, n)
        SingleScreen.set(false, SingleScreen.Why.DEVICE, at = 400L)       // off: no why
        assertEquals(3, n); assertFalse(SingleScreen.inUse); assertNull(SingleScreen.why)
        SingleScreen.offChange(l)
        SingleScreen.set(true, null, at = 500L)                           // on with no why = the button's
        assertEquals(3, n); assertEquals(SingleScreen.Why.USER, SingleScreen.why)
        assertEquals(4, SingleScreen.changes)
        assertTrue(SingleScreen.switchedLately(600L)); assertFalse(SingleScreen.switchedLately(500L + SingleScreen.HEAL_MS + 1))
    }

    @Test fun aListenerThatThrowsNeverStopsTheOthers() {
        var heard = false
        SingleScreen.onChange { throw IllegalStateException("x") }
        SingleScreen.onChange { heard = true }
        SingleScreen.set(true, SingleScreen.Why.USER)
        assertTrue(heard)
    }

    @Test fun theReportLine() {
        assertEquals("One screen: off, button hidden, switches 0", SingleScreen.line())
        SingleScreen.offered = true
        SingleScreen.set(true, SingleScreen.Why.USER)
        assertEquals("One screen: on (the button), button offered, switches 1", SingleScreen.line())
        SingleScreen.noteKeys(away = true); SingleScreen.noteKeys(back = true)
        assertTrue(SingleScreen.line().endsWith(", the other screen took the controller 1 (back 1, stayed 0)"))
        SingleScreen.set(true, SingleScreen.Why.DEVICE)
        assertTrue(SingleScreen.line().startsWith("One screen: on (no other screen)"))
    }

    @Test fun theTimingsTheSpecAsksFor() {
        assertEquals(400L, SingleScreen.KEYS_BACK_MS); assertEquals(1_500L, SingleScreen.KEYS_STUCK_MS)
        assertEquals(800L, SingleScreen.SWITCH_FREE_WAIT_MS); assertEquals(2_000L, SingleScreen.DEVICE_RETRY_MS)
        assertEquals("a one-screen start's grace (the review)", 3_000L, SingleScreen.ONE_GRACE_MS)
        assertTrue("R1's built default: the game keeps the controller", SingleScreen.KEYS_BACK)
    }

    @Test fun pureNoAndroid() {
        val src = listOf(File("../companion/app/src/main/java/com/jrees/wowcompanion"), File("companion/app/src/main/java/com/jrees/wowcompanion"))
            .first { it.isDirectory }
        for (f in listOf("SingleScreen.kt", "SingleLook.kt", "SingleUi.kt"))
            assertFalse("$f is pure", File(src, f).readText().contains("import android."))
    }
}
