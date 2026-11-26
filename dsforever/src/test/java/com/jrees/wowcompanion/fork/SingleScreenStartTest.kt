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
        assertTrue(d.contains("if (singleOffAtGameEnd(SingleScreen.why, relaunching)) {") && d.contains("rebuild()"))
        // the request hook is this DS Forever's while it runs
        assertTrue(source().substringAfter("fun start() {").substringBefore("fun stop() {").contains("SingleScreen.request = singleHook"))
        assertTrue(source().substringAfter("fun stop() {").substringBefore("/** the game screen is up").contains("if (SingleScreen.request === singleHook) SingleScreen.request = null"))
        assertTrue("Streaming's own DS Forever", source().contains("current = DsForeverScreen(activity).also { it.streamOwned = true; it.start() }"))
    }

    /**
     * THE REVIEW (2026-11-25, "coming back from the bottom launcher's icon breaks single mode"): Android brings the task back on display 4
     * and update() ran there before moveToTop's move landed - the top window opened on the bottom screen with its size, the button hidden;
     * and the move's relaunch turned the button's mode off as if the game had ended. Now update() opens nothing while the launcher's
     * activity is off display 0 in single screen mode (looked at again), a top window whose size or display is no more the activity's
     * is made again, and a configuration relaunch keeps the button's mode
     */
    @Test fun aReopenFromTheBottomScreenKeepsSingleScreenMode() {
        val u = source().substringAfter("private fun update() {").substringBefore("/** our window is down")
        val wait = u.indexOf("if (singleWaitsForTop(SingleScreen.inUse, swap, game)) {")
        assertTrue("the wait is decided before the mode's own steps", wait in 0 until u.indexOf("when (singleStep("))
        assertTrue("a stale top window is made again", u.contains("if (shown !is TopWindow || (shown as TopWindow).stale()) {"))
        val e = source().substringAfter("fun end(activity: Activity) {").substringBefore("\n        }")
        assertTrue("a configuration relaunch keeps the mode", e.contains("it.relaunching = activity.isChangingConfigurations; it.stop()"))
        val d = source().substringAfter("fun detachGame() {").substringBefore("/** back from Streaming")
        assertTrue(d.contains("if (singleOffAtGameEnd(SingleScreen.why, relaunching)) {"))
        val top = source("TopWindow.kt")
        assertTrue("the top window says its real display", top.contains("override val displayId: Int get() = activity.display?.displayId ?: Display.DEFAULT_DISPLAY"))
        assertTrue(top.contains("fun stale(): Boolean"))
    }

    /**
     * THE REVIEW (2026-11-25): the switch looks again where Android's keys are (one screen with the keys on the other screen: taken back)
     * and for a fight that began while the wheel's controller was let go (refused then, never a teardown in a fight)
     */
    /**
     * THE REVIEW (2026-11-25, "losing the bottom display switches single mode on by itself on a Thor"): the user ruled one-screen start
     * for a device that HAS one screen. A second screen seen once in this process (the Thor's bottom screen) = a two-screen device: its
     * loss closes the bottom window as before (566d3ece1), no single screen mode; and a one-screen start waits a grace (a late display)
     */
    @Test fun aSecondScreenSeenThenLostIsNoSingleScreenMode() {
        assertEquals("the Thor's bottom screen gone a moment: the base way (no single screen mode)", SingleStep.NONE,
            DsForeverScreen.singleStep(false, MAIN, false, null, false, sawSecond = true, oneForMs = 60_000L))
        assertEquals("a one-screen start waits its grace", SingleStep.WAIT, DsForeverScreen.singleStep(false, MAIN, false, null, false, false, 0L))
        assertEquals(SingleStep.WAIT, DsForeverScreen.singleStep(false, MAIN, false, null, false, false, SingleScreen.ONE_GRACE_MS - 1))
        assertEquals("then on by itself", SingleStep.DEVICE_ON, DsForeverScreen.singleStep(false, MAIN, false, null, false, false, SingleScreen.ONE_GRACE_MS))
        assertEquals("a second screen while the device's: two screens again (unchanged)", SingleStep.DEVICE_OFF,
            DsForeverScreen.singleStep(true, MAIN, false, SingleScreen.Why.DEVICE, false, sawSecond = true, oneForMs = 0L))
        assertEquals("the button's mode with the screen gone: unchanged", SingleStep.NONE,
            DsForeverScreen.singleStep(false, MAIN, false, SingleScreen.Why.USER, false, sawSecond = true, oneForMs = 60_000L))
    }

    @Test fun theWindowWaitsForTheMoveToTheTopScreen() {
        assertTrue("single, the activity on display 4", DsForeverScreen.singleWaitsForTop(inUse = true, swap = false, game = 4))
        assertFalse("on display 0", DsForeverScreen.singleWaitsForTop(true, false, MAIN))
        assertFalse("two screens: the base way", DsForeverScreen.singleWaitsForTop(false, false, 4))
        assertFalse("Swap screens: the game on the second screen is its own", DsForeverScreen.singleWaitsForTop(true, true, 4))
        assertFalse("a relaunch is no end of the game", DsForeverScreen.singleOffAtGameEnd(SingleScreen.Why.USER, relaunch = true))
        assertTrue(DsForeverScreen.singleOffAtGameEnd(SingleScreen.Why.USER, relaunch = false))
    }

    @Test fun aLostBottomScreenIsNoSingleScreenModeOnTheThor() {
        val u = source().substringAfter("private fun update() {").substringBefore("/** our window is down")
        assertTrue("a second screen seen is latched", u.contains("if (pick != null) sawSecond = true"))
        assertTrue("the step reads the latch and the grace",
            u.contains("singleStep(pick != null, game, swap, SingleScreen.why, SingleScreen.fightNow(), sawSecond, oneForMs(pick == null, android.os.SystemClock.uptimeMillis()))"))
        assertTrue("the grace's look again", u.contains("SingleStep.WAIT -> { main.removeCallbacks(deviceRetry); main.postDelayed(deviceRetry, SingleScreen.ONE_GRACE_MS) }"))
    }

    @Test fun theSwitchLooksAgainAtTheKeysAndTheFight() {
        val t = source().substringAfter("internal fun toggleSingle(").substringBefore("/** one of our activities is up")
        val post = t.substringAfter("main.post {")
        assertTrue("a fight now: refused inside the post too", post.indexOf("if (SingleScreen.fightNow()) {") in 0 until post.indexOf("SingleScreen.set("))
        assertTrue("one screen with the keys away: taken back", post.indexOf("if (on && !topNow) keysTop(false)") > post.indexOf("update()"))
    }

    private fun source(name: String = "DsForeverScreen.kt"): String =
        listOf("src/main/java/com/jrees/wowcompanion/fork/$name", "dsforever/src/main/java/com/jrees/wowcompanion/fork/$name")
            .map { java.io.File(it) }.first { it.canRead() }.readText()
}
