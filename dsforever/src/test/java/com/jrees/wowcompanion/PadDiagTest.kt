package com.jrees.wowcompanion

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issues #16 / #84 / #116 / #147 (the controller cluster, 2026-11-12): none of the four reports could say whether a press ever reached
 * DS Forever. PadDiag keeps the first press per device and route, the launcher's verdict on the device, the game screen's focus and one
 * report line; AddonSilence says when the bottom screen had no add-on document at all.
 */
class PadDiagTest {
    private val t0 = 1_700_000_000_000L
    private val odin = PadDiag.Dev(7, "Odin Controller", 0x2020, 0x0001, 0x01000511)        // gamepad | joystick | keyboard
    private val kb = PadDiag.Dev(3, "gpio-keys", 0, 0, 0x00000101)                            // the Thor's volume keys: a keyboard

    @Before fun fresh() {
        DiagLog.useDir(Files.createTempDirectory("diag").toFile())
        PadDiag.reset()
    }

    private fun lines() = DiagLog.lines(3_600_000L, t0 + 3_600_000L)

    @Test fun theFirstPressFromAControllerIsLoggedOnceAndCounted() {
        val l = PadDiag.note(odin, 96, controller = true, route = "game screen", now = t0)
        assertTrue(l!!, l.contains("'Odin Controller' (2020:0001, gamepad)") && l.contains("a controller, DS Forever reads it") && l.contains("via game screen"))
        assertNull("only the first press", PadDiag.note(odin, 97, controller = true, route = "game screen", now = t0 + 1_000))
        assertEquals(2, PadDiag.presses)
        assertEquals(1, lines().count { it.contains("pad: first press from 'Odin Controller'") })
    }

    @Test fun anotherRouteForTheSameDeviceIsItsOwnLine() {
        PadDiag.note(odin, 96, true, "game screen", t0)
        val l = PadDiag.note(odin, 96, true, "the bottom window (typing)", t0 + 500)
        assertTrue(l!!, l.contains("via the bottom window (typing)"))
    }

    @Test fun aPadTheLauncherRefusesIsSaidLoudly() {
        val dev = PadDiag.Dev(9, "Generic HID", 0x1234, 0x5678, 0x00000101)
        val l = PadDiag.note(dev, 96, controller = false, route = "game screen", now = t0)
        assertTrue(l!!, l.contains("NOT a controller to the launcher"))
        assertEquals("a refused pad's presses are not counted as read", 0, PadDiag.presses)
        assertTrue(PadDiag.line(t0).contains("(not a controller)"))
    }

    @Test fun theBottomWindowNeverGivesTheLaunchersVerdict() {
        // review: a D-pad press from the Thor's pad as DPAD|KEYBOARD on the bottom window is not "NOT a controller to the launcher"
        val l = PadDiag.note(odin, 19, PadDiag.padDevice(odin.sources), "the bottom window", t0, judged = false)
        assertTrue(l!!, l.contains("via the bottom window") && l.contains("the start-up keys read it here") && !l.contains("NOT a controller"))
        val l2 = PadDiag.note(PadDiag.Dev(4, "Generic HID", 0x1234, 0x5678, 0x00000101), 19, false, "the bottom window", t0, judged = false)
        assertTrue(l2!!, l2.contains("(not a pad device)") && !l2.contains("NOT a controller"))
        assertFalse(PadDiag.line(t0).contains("(not a controller)"))
        assertTrue(PadDiag.padDevice(odin.sources))
        assertFalse(PadDiag.padDevice(0x00000101 or 0x00000201))       // keyboard | dpad: not a pad device
    }

    @Test fun keyboardLettersAreNotTheControllersBusiness() {
        assertNull(PadDiag.note(kb, 24, controller = false, route = "game screen", now = t0))     // volume up
        assertNull(PadDiag.note(kb, 29, controller = false, route = "game screen", now = t0))     // a letter
        assertTrue(lines().none { it.contains("pad:") })
    }

    @Test fun aNameThePlayerGaveIsNotKept() {
        assertEquals("a named device (054c:0ce6, gamepad)", PadDiag.label(PadDiag.Dev(1, "Tom's DualSense", 0x054c, 0x0ce6, 0x00000401)))
        assertEquals("an unknown device", PadDiag.label(null))
        assertEquals("'Xbox Wireless Controller' (045e:0b13, joystick)", PadDiag.label(PadDiag.Dev(2, "Xbox Wireless Controller", 0x045e, 0x0b13, 0x01000010)))
    }

    @Test fun theFocusIsLoggedOnlyWhenItChanges() {
        assertNull("the normal start says nothing", PadDiag.focusNote(true, false, t0))
        assertNull(PadDiag.focusNote(true, false, t0 + 1_000))
        val lost = PadDiag.focusNote(false, false, t0 + 2_000)
        assertTrue(lost!!, lost.contains("lost the focus") && lost.contains("another window"))
        assertNull(PadDiag.focusNote(false, false, t0 + 3_000))
        assertTrue(PadDiag.line(t0 + 62_000).endsWith("game screen focus: NO for 1 min"))
        assertEquals("the game screen has the focus again", PadDiag.focusNote(true, false, t0 + 70_000))
        val typing = PadDiag.focusNote(false, true, t0 + 80_000)
        assertTrue(typing!!, typing.contains("text field"))
        assertEquals(3, lines().count { it.contains("pad: the game screen") })
    }

    @Test fun theReportLine() {
        assertEquals("Controller: no controller press read this run; devices: none seen; game screen focus: ?", PadDiag.line(t0))
        PadDiag.note(odin, 96, true, "game screen", t0)
        PadDiag.focusNote(true, false, t0)
        assertEquals("Controller: 1 press read, the last 5 s ago; devices: 'Odin Controller' (2020:0001, gamepad); game screen focus: yes",
            PadDiag.line(t0 + 5_000))
    }

    @Test fun theReportLineSurvivesThePrivacyPass() {
        ReportUpload.endpoint = ""
        PadDiag.note(odin, 96, true, "game screen", t0)
        val line = PadDiag.line(t0 + 1_000)
        val facts = ProblemReport.Facts("1.0.28 (1442)", "set 378", "wowf 1.60", "AYN Thor, Android 13", listOf("Game running: true", line))
        val report = ProblemReport.build(0L, "no controller input", facts, emptyList(), emptyList(), emptyList(), false, listOf("Tomsot"))
        val text = report.files.first { it.first == "report.txt" }.second
        assertTrue(text, text.contains("  $line"))
    }

    @Test fun theAddOnsSilence() {
        assertNull("too soon", AddonSilence.line(false, true, 60_000L, 600_000L))
        assertNull("documents came", AddonSilence.line(true, true, 300_000L, 600_000L))
        assertNull("no game", AddonSilence.line(false, false, 300_000L, 0L))
        val l = AddonSilence.line(false, true, 125_000L, 14 * 60_000L)!!
        assertTrue(l, l.startsWith("no current document from the game's add-ons 2 min after the bottom screen came up (the game up 14 min)"))
        assertFalse(AddonSilence.line(false, true, 125_000L, 0L)!!.contains("up 0"))
    }
}
