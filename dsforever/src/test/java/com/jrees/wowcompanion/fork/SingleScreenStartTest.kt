package com.jrees.wowcompanion.fork

import com.jrees.wowcompanion.SingleScreen
import com.jrees.wowcompanion.fork.DsForeverScreen.Companion.SingleStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SINGLE SCREEN MODE'S START RULES (the user's picks, 2026-11-25: "Two screen start however if its a single screen device then it starts
 * in single screen mode"; one-screen devices "Yes, automatically"): the Thor starts with two screens, a device with nothing but display 0
 * starts in single screen mode by itself, a second screen that comes ends that (never in a fight), the button's choice lives until the
 * game ends; Swap screens is never single; the window update() opens follows the mode; the switch's own guard.
 */
class SingleScreenStartTest {
    private val MAIN = 0
    @After fun clean() = SingleScreen.reset()

    @Test fun theThorStartsWithTwoScreens() {
        assertEquals(SingleStep.NONE, DsForeverScreen.singleStep(otherScreen = true, game = MAIN, swap = false, why = null, fight = false))
        assertFalse(SingleScreen.inUse)
    }

    @Test fun displayZeroAloneStartsInSingleScreenMode() {
        assertEquals(SingleStep.DEVICE_ON, DsForeverScreen.singleStep(otherScreen = false, game = MAIN, swap = false, why = null, fight = false))
        // and stays (no second ask)
        assertEquals(SingleStep.NONE, DsForeverScreen.singleStep(false, MAIN, false, SingleScreen.Why.DEVICE, false))
        // a fight at the start changes nothing: one screen is all there is
        assertEquals(SingleStep.DEVICE_ON, DsForeverScreen.singleStep(false, MAIN, false, null, fight = true))
    }

    @Test fun aSecondScreenEndsTheDevicesModeButNotInAFight() {
        assertEquals(SingleStep.DEVICE_OFF, DsForeverScreen.singleStep(true, MAIN, false, SingleScreen.Why.DEVICE, fight = false))
        assertEquals("looked at again a moment later", SingleStep.RETRY, DsForeverScreen.singleStep(true, MAIN, false, SingleScreen.Why.DEVICE, fight = true))
    }

    @Test fun theButtonsChoiceStaysUntilTheGameEnds() {
        // a hide / show (pause / resume) never asks again: USER stays with a second screen, and with it gone
        assertEquals(SingleStep.NONE, DsForeverScreen.singleStep(true, MAIN, false, SingleScreen.Why.USER, false))
        assertEquals(SingleStep.NONE, DsForeverScreen.singleStep(false, MAIN, false, SingleScreen.Why.USER, false))
        assertTrue("the game's end: two screens again", DsForeverScreen.singleOffAtGameEnd(SingleScreen.Why.USER))
        assertFalse("a one-screen device keeps it", DsForeverScreen.singleOffAtGameEnd(SingleScreen.Why.DEVICE))
        assertFalse(DsForeverScreen.singleOffAtGameEnd(null))
    }

    @Test fun swapScreensIsNeverSingle() {
        // Swap screens with one screen: the game on display 0 and nothing to swap (the existing rule) - no single screen mode by itself
        assertEquals(SingleStep.NONE, DsForeverScreen.singleStep(false, MAIN, swap = true, why = null, fight = false))
        // the game on the second screen (Swap screens in use)
        assertEquals(SingleStep.NONE, DsForeverScreen.singleStep(false, 4, swap = true, why = null, fight = false))
    }

    @Test fun theSwitchsGuard() {
        val ok = DsForeverScreen.singleSwitchAllowed(running = true, hidden = false, current = true, stream = false, why = null, on = true)
        assertTrue(ok)
        assertFalse("not running", DsForeverScreen.singleSwitchAllowed(false, false, true, false, null, true))
        assertFalse("the launcher away", DsForeverScreen.singleSwitchAllowed(true, true, true, false, null, true))
        assertFalse("another DS Forever's", DsForeverScreen.singleSwitchAllowed(true, false, false, false, null, true))
        assertFalse("Streaming's (R2)", DsForeverScreen.singleSwitchAllowed(true, false, true, true, null, true))
        assertFalse("a one-screen device has nowhere to go", DsForeverScreen.singleSwitchAllowed(true, false, true, false, SingleScreen.Why.DEVICE, false))
        assertTrue("the button's own off", DsForeverScreen.singleSwitchAllowed(true, false, true, false, SingleScreen.Why.USER, false))
    }

    @Test fun updateOpensTheWindowTheModeWants() {
        val u = source().substringAfter("private fun update() {").substringBefore("/** our window is down")
        val single = u.substringAfter("when (singleStep(").substringBefore("if (pick == null) {")
        assertTrue("the button shows with a second screen", single.contains("SingleScreen.offered = pick != null"))
        assertTrue("single: the top window, nothing on the other screen", single.contains("if (SingleScreen.inUse) {") &&
            single.contains("shown = openTop()") && single.contains("companionGone()") && single.contains("return"))
        assertTrue("decided before the two-screen code", u.indexOf("when (singleStep(") in 0 until u.indexOf("if (pick == null) {"))
        assertTrue("a fight's retry is one runnable", single.contains("main.removeCallbacks(deviceRetry); main.postDelayed(deviceRetry, SingleScreen.DEVICE_RETRY_MS)"))
        // the switch: one host at a time (the old window closes before update() opens the other), posted
        val t = source().substringAfter("internal fun toggleSingle(").substringBefore("/** one of our activities is up")
        assertTrue(t.contains("main.post {"))
        assertTrue(t.indexOf("shown?.close(); shown = null; companionGone()") in 0 until t.indexOf("update()"))
        assertTrue("GameUi's taps wait", t.contains("GameUi.holdTaps("))
        // the game's end
        val d = source().substringAfter("fun detachGame() {").substringBefore("/** back from Streaming")
        assertTrue(d.contains("if (singleOffAtGameEnd(SingleScreen.why)) {") && d.contains("rebuild()"))
        // the request hook is this DS Forever's while it runs
        assertTrue(source().substringAfter("fun start() {").substringBefore("fun stop() {").contains("SingleScreen.request = singleHook"))
        assertTrue(source().substringAfter("fun stop() {").substringBefore("/** the game screen is up").contains("if (SingleScreen.request === singleHook) SingleScreen.request = null"))
        assertTrue("Streaming's own DS Forever", source().contains("current = DsForeverScreen(activity).also { it.streamOwned = true; it.start() }"))
    }

    private fun source(name: String = "DsForeverScreen.kt"): String =
        listOf("src/main/java/com/jrees/wowcompanion/fork/$name", "dsforever/src/main/java/com/jrees/wowcompanion/fork/$name")
            .map { java.io.File(it) }.first { it.canRead() }.readText()
}
