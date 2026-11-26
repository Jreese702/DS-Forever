package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SINGLE SCREEN MODE, OUR OWN ACTIVITIES (spec 8.4: the add-on import, the frame-gen picker, the microphone question): on a two-screen
 * device they open on the OTHER screen (Android's launcher is the bottom screen there) with the plate "On the bottom screen", the top
 * window never hidden for them and the controller's keys left alone meanwhile (SingleScreen.keysPaused, until the activity's close hook,
 * then the keys looked at again); a one-screen device opens them on display 0 (the launcher pauses, the window comes back with it);
 * two screens: the old route exactly.
 */
class OpenOnBottomSingleTest {
    @After fun down() { SingleScreen.reset() }

    @Test fun whereTheyOpen() {
        assertEquals(SingleNpc.Bottom.TWO_SCREENS, SingleNpc.bottomRoute(single = false, other = 4))
        assertEquals(SingleNpc.Bottom.TWO_SCREENS, SingleNpc.bottomRoute(single = false, other = null))
        assertEquals(SingleNpc.Bottom.OTHER, SingleNpc.bottomRoute(single = true, other = 4))
        assertEquals("one screen: display 0", SingleNpc.Bottom.HERE, SingleNpc.bottomRoute(single = true, other = null))
        assertEquals("never display 0 as the other", SingleNpc.Bottom.HERE, SingleNpc.bottomRoute(single = true, other = 0))
    }

    @Test fun theKeysAreAskedAgainAfterTheClose() {
        var asked = 0
        SingleScreen.keysAgain = { asked++ }
        SingleScreen.keysAgain?.invoke()
        assertEquals(1, asked)
        SingleScreen.reset()
        assertEquals("tests: reset", null, SingleScreen.keysAgain)
        assertFalse(SingleScreen.keysPaused)
    }

    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val host = File(src, "CompanionHost.kt").readText()

    @Test fun theHostsRoute() {
        val open = host.substringAfter("private fun openOnBottom(cls: Class<*>, tag: String, setClosed: ((() -> Unit)?) -> Unit): Boolean {")
            .substringBefore("\n    }")
        // single screen mode first, through NPC's route; two screens: the old lines below it, untouched
        assertTrue(open.lines().drop(1).first { it.isNotBlank() }.trim() == "singleOpenOnBottom(cls, tag, setClosed)?.let { return it }")
        // (BottomSync's review, 2026-11-26: the two-screen hide goes through the window when it can - a parked one never shows - else the decor)
        assertTrue(open.contains("if (!shell.hostHeld(on)) decor?.visibility = if (on) View.GONE else View.VISIBLE"))
        val single = host.substringAfter("private fun singleOpenOnBottom(").substringBefore("\n    }")
        assertTrue(single.contains("SingleNpc.bottomRoute(true, s.otherDisplay)"))
        assertTrue("never the top window's decor (its look is the decor)", !single.contains("decorView") && !single.contains("visibility ="))
        assertTrue("the keys left alone while it is up", single.contains("SingleScreen.keysPaused = true"))
        assertTrue("and looked at again when it closes", single.contains("SingleScreen.keysPaused = false; SingleScreen.keysAgain?.invoke()"))
        assertTrue(single.contains("setLaunchDisplayId(other)"))
        assertTrue(single.contains("hint(SingleNpc.ON_BOTTOM, SingleNpc.ON_BOTTOM_MS)"))
        // DsForeverScreen's side: the last top-resumed answer kept, the ask re-runs keysTop(false) only while the game is not on top
        val fork = listOf("src/main/java/com/jrees/wowcompanion/fork", "dsforever/src/main/java/com/jrees/wowcompanion/fork").map { File(it) }
            .first { it.isDirectory }
        val screen = File(fork, "DsForeverScreen.kt").readText()
        assertTrue(screen.contains("private val keysAgainHook: () -> Unit = { main.post { if (!topNow) keysTop(false) } }"))
        assertTrue("set with the wheel's ask", screen.substringAfter("    fun start() {").substringBefore("\n    }").contains("SingleScreen.keysAgain = keysAgainHook"))
        assertTrue("and let go at the stop", screen.substringAfter("    fun stop() {").substringBefore("\n    }")
            .contains("if (SingleScreen.keysAgain === keysAgainHook) SingleScreen.keysAgain = null"))
        assertTrue(screen.substringAfter("internal fun keysTop(top: Boolean) {").substringBefore("\n    }").contains("topNow = top"))
    }
}
