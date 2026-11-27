package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

/**
 * THE TAP-TO-CAST BAR'S B (single screen mode's bottom-left corner button; the user's pick 2026-11-26: "Yes, B closes it"): while the
 * corner's bar is open Home holds the controller as the generic guest "home" (the map module's and the buff list's key path), B's press
 * closes the bar and the hold goes with it; with the bar shut nothing is held and a B is never the bar's; a hold the game refuses (a
 * fight) is let go at once and Home's own B watch closes the bar instead; another of Home's windows holding the guest keeps it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DockCastBTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun press(k: String, n: Int) = JSONObject().put("type", "guestpad").put("id", "home").put("k", k).put("n", n)

    @Test fun bClosesTheBarOnlyWhileItIsOpen() {
        val s = SinglePlayStage(app, partyOn = false, castBar = true)
        val h = s.home
        var closes = 0
        h.onDockCastClose = { closes++; h.dockCast(false) }
        assertFalse("shut: nothing of the bar's held", h.homeOwnGuestWanted())
        h.auraPad(press("PAD2", 1))
        assertEquals("shut: a B is never the bar's", 0, closes)

        h.dockCast(true)
        assertEquals("open: the guest \"home\" asked for", listOf("hold"), h.dockCastAsks)
        assertTrue("open: Home wants its guest (the host never frees it meanwhile)", h.homeOwnGuestWanted())
        h.auraGuestAck(JSONObject().put("type", "guestack").put("id", "home").put("op", "hold").put("ok", true))
        assertTrue(h.dockCastHeld)
        h.auraPad(press("PADDDOWN", 2))
        h.auraPad(press("PAD1", 3))
        assertEquals("the D-pad and A do not close it (dropped while it holds)", 0, closes)
        h.auraPad(press("PAD2", 4))
        assertEquals("B closes it", 1, closes)
        assertEquals("closed: the hold let go at once", listOf("hold", "free"), h.dockCastAsks)
        assertFalse(h.homeOwnGuestWanted()); assertFalse(h.dockCastHeld)
        h.auraPad(press("PAD2", 4))
        h.auraPad(press("PAD2", 5))
        assertEquals("a later B is the game's again", 1, closes)
    }

    @Test fun aRefusedHoldIsLetGoAndTheWatchClosesTheBar() {
        val s = SinglePlayStage(app, partyOn = false, castBar = true)
        val h = s.home
        var closes = 0
        h.onDockCastClose = { closes++; h.dockCast(false) }
        h.dockCast(true)
        h.auraGuestAck(JSONObject().put("type", "guestack").put("id", "home").put("op", "hold").put("ok", false).put("why", "combat"))
        assertEquals("refused (a fight): let go at once", listOf("hold", "free"), h.dockCastAsks)
        assertFalse(h.dockCastHeld)
        // Home's own B watch (the band's reader, read only) closes the bar then
        HomeView::class.java.getDeclaredMethod("onB").apply { isAccessible = true }.invoke(h)
        assertEquals(1, closes)
        assertFalse(h.homeOwnGuestWanted())
    }

    @Test fun theHostShutsItEveryWay() {
        val src = listOf(File("../companion/app/src/main/java/com/jrees/wowcompanion/CompanionHost.kt"),
            File("companion/app/src/main/java/com/jrees/wowcompanion/CompanionHost.kt")).first { it.canRead() }.readText()
        assertEquals("castOpen is set in castSet alone (its declaration aside)", 1, Regex("(?<!var )castOpen = ").findAll(src).count())
        assertTrue("castSet hands Home the bar's state (its hold)", src.contains("if (::homeView.isInitialized) homeView.dockCast(open)"))
        for (why in listOf("\"the play docks went\"", "\"no tap-to-cast bar set up\"", "\"B\"", "\"the host goes\""))
            assertTrue("shut by $why", src.contains("castSet(false, $why)"))
        assertTrue(src.contains("homeView.onDockCastClose = { if (castOpen) { castSet(false, \"B\"); playStep(animate = true) } }"))
    }
}
