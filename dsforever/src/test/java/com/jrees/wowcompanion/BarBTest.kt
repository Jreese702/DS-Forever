package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BAR B (the user, 2026-11-16, 1.0.33 code 1476: "pressing Dpad down out of combat followed by B (while its on the spiral) is opening the
 * pause menu? it should just be closing the dpad select"), off-device. 12:43:22-25 of the user's log: D-pad Down on Home -> "bar: open on
 * Home EARLY" -> B -> "bar: press PAD2 ... on HOME" -> "bar: opens HOME" -> "pause: the wheel over HOME (the bar's swirl)". The D-pad's
 * Down puts Home's disc in the cursor's order by the same flag that meant "the cursor came up in a room", and B on the disc in a room was
 * the swirl's pick (the pause wheel since 2026-10-09). Now: on Home B only closes the cursor; from a room B on the disc goes Home (the user
 * 2026-10-01: "Yes, B on the Home disc goes Home"); A on the disc stays the pause wheel everywhere (2026-10-09).
 */
class BarBTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val bar = File(src, "HomeBarSelect.kt").readText()
    private val host = File(src, "CompanionHost.kt").readText()

    private fun body(text: String, head: String): String {
        val i = text.indexOf(head)
        assertTrue("'$head' not found", i >= 0)
        val end = listOf("\n    /**", "\n    private fun ", "\n    fun ").map { text.indexOf(it, i + head.length) }.filter { it > 0 }.minOrNull()
        return text.substring(i, end ?: text.length)
    }

    private val rooms = Room.values().filter { it != Room.HOME }

    // ---------------------------------------------------------------- the model

    @Test fun bOnTheSwirlOnHomeOnlyClosesTheCursor() = assertEquals(BarB.B.CLOSE, BarB.onB(Room.HOME, inRoom = false))

    @Test fun bOnARoomOnHomeClosesTheCursor() = rooms.forEach { assertEquals(it.name, BarB.B.CLOSE, BarB.onB(it, inRoom = false)) }

    @Test fun bOnTheSwirlFromARoomGoesHomeNotToThePauseWheel() {
        assertEquals(BarB.B.HOME, BarB.onB(Room.HOME, inRoom = true))
        assertEquals(BarB.Go.HOME, BarB.go(Room.HOME, byB = true))
    }

    @Test fun bOnARoomFromARoomClosesTheCursor() = rooms.forEach { assertEquals(it.name, BarB.B.CLOSE, BarB.onB(it, inRoom = true)) }

    @Test fun aOnTheSwirlIsThePauseWheel() = assertEquals(BarB.Go.PAUSE, BarB.go(Room.HOME, byB = false))

    @Test fun aOnARoomOpensThatRoom() = rooms.forEach { assertEquals(it.name, BarB.Go.ROOM, BarB.go(it, byB = false)) }

    // ---------------------------------------------------------------- the selector's wiring

    @Test fun theDpadDownCursorOnHomeIsNotInARoom() {
        // b98's early open (the log's route) and the "home" command's own open on Home: the disc is a stop, B there no pick
        val early = body(bar, "fun openEarly(")
        assertTrue("openEarly keeps the disc in the order", early.contains("fromRoom = true"))
        assertTrue("openEarly: B on the disc closes", early.contains("inRoom = false"))
        assertTrue("openOnHome: in a room only when one is shown", body(bar, "fun openOnHome(").contains("inRoom = shown != Room.HOME"))
    }

    @Test fun everyOtherOpenSaysWhetherARoomShows() {
        assertTrue("Select's open", body(bar, "fun onSelect(").contains("inRoom = roomNow() != Room.HOME"))
        assertTrue("left / right commands", body(bar, "fun onCommand(").contains("inRoom = shown != Room.HOME"))
    }

    @Test fun bReadsInRoomNotTheDiscStop() {
        val pad = body(bar, "fun onPad(")
        assertTrue("PAD2 decides by BarB.onB(at, inRoom)", pad.contains("\"PAD2\" -> when (BarB.onB(at, inRoom))"))
        assertTrue("B's Home is a pick by B", pad.contains("BarB.B.HOME -> pick(byB = true)"))
        assertTrue("B's close", pad.contains("BarB.B.CLOSE -> close()"))
        assertFalse("1476's rule: B on the disc = the swirl's pick wherever the disc is a stop", pad.contains("fromRoom) pick()"))
    }

    @Test fun aIsStillThePlainPick() {
        assertTrue(body(bar, "fun onPad(").contains("\"PAD1\" -> pick()"))
        assertTrue("the Open command is A's", bar.contains("\"open\" -> if (open) pick()"))
        val pick = body(bar, "private fun pick(")
        assertTrue("the pick tells the host what it opens", pick.contains("val go = BarB.go(r, byB)") && pick.contains("onOpen(r, go)"))
    }

    @Test fun theHostOpensThePauseWheelOnlyForAPausePick() {
        val line = host.lines().single { it.contains("barSelect = HomeBarSelect(") }
        assertTrue(line, line.contains("go == BarB.Go.PAUSE) openPause(\"the bar's swirl\", byStart = false)"))
        assertTrue(line, line.contains("else pickRoom(r)"))
        assertFalse("1476: every HOME pick was the pause wheel", line.contains("r == Room.HOME) openPause"))
    }
}
