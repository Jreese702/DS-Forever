package com.jrees.wowcompanion.fork

import com.jrees.wowcompanion.SingleScreen
import com.jrees.wowcompanion.fork.DsForeverScreen.Companion.BottomAct
import com.jrees.wowcompanion.fork.DsForeverScreen.Companion.SingleStep
import com.jrees.wowcompanion.fork.DsForeverScreen.Companion.Win
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE BOTTOM SCREEN COMES STRAIGHT BACK (the user, 2026-11-25: "Coming back after a drop: When the bottom screen goes blank or restarts,
 * DS Forever should come straight back on it, in the same room, already caught up with the game, with no app restart"; "Like most of the
 * time the drops happen from minimizing the game or closing my thor to sleep"). A minimize or a sleep PARKS the bottom window (the same
 * window and the same CompanionHost, hidden) and the return UNPARKS it; a window Android closed by itself is noticed and opened again
 * by a watchdog with a backoff and a loop guard; a display that comes back with a new id is moved to; a parked host is let go only on
 * Android's real memory pressure. Pure: [Sim] plays DsForeverScreen's steps over a fake window and counts the hosts made.
 */
class BottomComebackTest {
    private val MAIN = 0
    private val BOTTOM = 4
    @After fun clean() = SingleScreen.reset()

    /**
     * DsForeverScreen's two-screen path over a fake window: [hide] / [show] (the launcher's pause / resume), [display] (a display
     * event: the listener's update), [androidCloses] (Android dismissing the window by itself). Counts the CompanionHosts made and
     * checks after every step that never two are alive (a MOVE / CLOSE closes before an OPEN).
     */
    private class Sim(var pick: Int? = 4, var game: Int = 0) {
        var win: Win? = null
        var hidden = false
        var hostsMade = 0
        var hostsAlive = 0
        val acts = ArrayList<BottomAct>()
        private fun open(at: Int) { check(hostsAlive == 0) { "two hosts" }; hostsMade++; hostsAlive++; win = Win(at, alive = true, parked = false) }
        private fun close() { if (win?.alive == true) hostsAlive--; win = null }
        fun step() {
            // F1: a window Android closed is forgotten first (DsForeverScreen.lost / update's alive check)
            if (win?.alive == false) win = null
            val a = DsForeverScreen.bottomStep(hidden, if (hidden) null else pick, game, win)
            acts.add(a)
            when (a) {
                BottomAct.NONE, BottomAct.KEEP_PARKED -> {}
                BottomAct.PARK -> win = win!!.copy(parked = true)
                BottomAct.UNPARK -> win = win!!.copy(parked = false)
                BottomAct.CLOSE -> close()
                BottomAct.MOVE -> { close(); open(pick!!) }
                BottomAct.OPEN -> open(pick!!)
            }
            assertTrue("never two hosts", hostsAlive <= 1)
        }
        fun hide() { hidden = true; step() }
        fun show() { hidden = false; step() }
        fun display() = step()
        fun androidCloses() { win = win?.copy(alive = false); hostsAlive = 0; step() }
    }

    @Test fun aMinimizeParksAndTheReturnUnparks() {
        val s = Sim().apply { show() }
        assertEquals(listOf(BottomAct.OPEN), s.acts)
        // Thor Home / controller Home: onUserLeaveHint, onPause, onStop all hide (the 2nd and 3rd change nothing)
        s.hide(); s.hide(); s.hide()
        assertEquals(listOf(BottomAct.PARK, BottomAct.NONE, BottomAct.NONE), s.acts.drop(1))
        assertTrue(s.win!!.parked)
        // back: the SAME host (no second one made), shown again
        s.show()
        assertEquals(BottomAct.UNPARK, s.acts.last())
        assertEquals("the same host", 1, s.hostsMade)
        assertFalse(s.win!!.parked)
    }

    @Test fun recentsAndAnotherAppOverTheGameAreTheSame() {
        // Recents: onUserLeaveHint + onPause + onStop; another app on top: onPause (+ onStop) only. Either way one park, one unpark
        for (hides in listOf(3, 1, 2)) {
            val s = Sim().apply { show() }
            repeat(hides) { s.hide() }
            s.show()
            assertEquals(1, s.hostsMade)
            assertEquals(1, s.acts.count { it == BottomAct.PARK }); assertEquals(1, s.acts.count { it == BottomAct.UNPARK })
        }
    }

    @Test fun sleepAndWakeOnTheSameDisplayUnpark() {
        val s = Sim().apply { show() }
        s.hide()                                   // screen off: onPause, then onStop
        s.display(); s.display()                   // display 4 OFF, display 0 OFF (onDisplayChanged while hidden: nothing)
        s.display()                                // display 4 ON before the resume
        s.show()                                   // onResume
        s.display()                                // a late STATE_ON after the resume: nothing more
        assertEquals(listOf(BottomAct.OPEN, BottomAct.PARK, BottomAct.NONE, BottomAct.NONE, BottomAct.NONE, BottomAct.UNPARK, BottomAct.NONE), s.acts)
        assertEquals("the same host", 1, s.hostsMade)
    }

    @Test fun theBottomPanelGoneWhileAsleepComesBackParkedOrNew() {
        // removed while asleep and back with the same id before the wake: the parked window is still Android's (alive): unparked
        val s = Sim().apply { show(); hide() }
        s.pick = null; s.display(); s.pick = 4; s.display()
        s.show()
        assertEquals(BottomAct.UNPARK, s.acts.last()); assertEquals(1, s.hostsMade)
        // Android cancelled it with the removal (Presentation's own listener): noticed, a new one at the wake
        val t = Sim().apply { show(); hide() }
        t.androidCloses()
        assertEquals("nothing while hidden", BottomAct.NONE, t.acts.last())
        t.show()
        assertEquals(BottomAct.OPEN, t.acts.last()); assertEquals(2, t.hostsMade)
    }

    @Test fun aDeadWindowIsNeverKept() {
        // F1: the same display but Android closed the window: open again (it used to return at "shown.displayId == pick.id")
        assertEquals(BottomAct.OPEN, DsForeverScreen.bottomStep(false, BOTTOM, MAIN, Win(BOTTOM, alive = false, parked = false)))
        assertEquals(BottomAct.OPEN, DsForeverScreen.bottomStep(false, BOTTOM, MAIN, Win(BOTTOM, alive = false, parked = true)))
        val s = Sim().apply { show() }
        s.androidCloses()                          // mid-game, the launcher showing: reopened at once
        assertEquals(BottomAct.OPEN, s.acts.last()); assertEquals(2, s.hostsMade)
    }

    @Test fun androidDismissingTheWindowTriggersAReopen() {
        val f = source()
        val lost = f.substringAfter("internal fun lost(").substringBefore("\n    }\n")
        assertTrue("forgotten", lost.contains("if (shown === w) { shown = null; companionGone() }"))
        assertTrue("the watchdog looks again", lost.contains("reopenSoon("))
        assertTrue("the guard counts Android's closes", lost.contains("cancels.note("))
        val p = f.substringAfter("private class HostPresentation(")
        val stop = p.substringAfter("override fun onStop() {").substringBefore("\n    }\n")
        assertTrue("Android's close = onStop without our close", stop.contains("val byAndroid = !closing"))
        assertTrue("a snapshot before the host goes", stop.indexOf("BottomSync.keep(") in 0 until stop.indexOf("it.onDestroy()"))
        assertTrue(stop.contains("onLost(this"))
        assertTrue("our close marks itself", p.contains("override fun close() { closing = true"))
    }

    @Test fun theDisplayGoneWhileShownCloses() {
        assertEquals(BottomAct.CLOSE, DsForeverScreen.bottomStep(false, null, MAIN, Win(BOTTOM, alive = true, parked = false)))
        assertEquals(BottomAct.CLOSE, DsForeverScreen.bottomStep(false, null, MAIN, Win(BOTTOM, alive = true, parked = true)))
    }

    @Test fun aNewIdMovesThere() {
        assertEquals(BottomAct.MOVE, DsForeverScreen.bottomStep(false, 5, MAIN, Win(BOTTOM, alive = true, parked = false)))
        assertEquals(BottomAct.MOVE, DsForeverScreen.bottomStep(false, 5, MAIN, Win(BOTTOM, alive = true, parked = true)))
        val s = Sim().apply { show(); hide() }
        s.pick = 7; s.show()
        assertEquals(BottomAct.MOVE, s.acts.last()); assertEquals(7, s.win!!.display); assertEquals(2, s.hostsMade)
    }

    @Test fun theLauncherOnTheBottomAMomentKeepsTheParkedWindow() {
        // F5: reopened from the bottom launcher's icon, the activity (the game) is on display 4 a moment: never unparked over the game,
        // never closed; the move to display 0 then unparks it
        assertEquals(BottomAct.KEEP_PARKED, DsForeverScreen.bottomStep(false, null, BOTTOM, Win(BOTTOM, alive = true, parked = true)))
        val s = Sim().apply { show(); hide() }
        s.game = BOTTOM; s.pick = null; s.show()
        assertEquals(BottomAct.KEEP_PARKED, s.acts.last())
        s.game = MAIN; s.pick = BOTTOM; s.display()
        assertEquals(BottomAct.UNPARK, s.acts.last()); assertEquals(1, s.hostsMade)
    }

    @Test fun nothingToDoWithNoWindowAndNoScreen() {
        assertEquals(BottomAct.NONE, DsForeverScreen.bottomStep(false, null, MAIN, null))
        assertEquals(BottomAct.NONE, DsForeverScreen.bottomStep(true, null, MAIN, null))
        assertEquals(BottomAct.NONE, DsForeverScreen.bottomStep(true, BOTTOM, MAIN, Win(BOTTOM, alive = true, parked = true)))
        assertEquals(BottomAct.PARK, DsForeverScreen.bottomStep(true, BOTTOM, MAIN, Win(BOTTOM, alive = true, parked = false)))
        assertEquals("a dead window while hidden: nothing (lost() forgets it)", BottomAct.NONE,
            DsForeverScreen.bottomStep(true, null, MAIN, Win(BOTTOM, alive = false, parked = false)))
        assertEquals(BottomAct.NONE, DsForeverScreen.bottomStep(false, BOTTOM, MAIN, Win(BOTTOM, alive = true, parked = false)))
        assertEquals(BottomAct.OPEN, DsForeverScreen.bottomStep(false, BOTTOM, MAIN, null))
    }

    @Test fun aFailedOpenIsTriedAgainWithABackoffAndALoopGuard() {
        // F3: 250, 500, 1000, 2000 ms, then every 5 s
        assertEquals(listOf(250L, 500L, 1_000L, 2_000L, 5_000L, 5_000L, 5_000L), (0..6).map { DsForeverScreen.reopenDelay(it, slow = false) })
        // the guard: Android closing it 3 times in 15 s = the slow cadence only (never an open loop)
        val g = DsForeverScreen.Companion.CancelGuard()
        val t = 100_000L
        assertFalse(g.slow(t))
        g.note(t); g.note(t + 1_000)
        assertFalse(g.slow(t + 1_500))
        g.note(t + 2_000)
        assertTrue(g.slow(t + 2_500))
        assertEquals(listOf(5_000L, 5_000L), (0..1).map { DsForeverScreen.reopenDelay(it, slow = true) })
        assertFalse("old closes no more count", g.slow(t + 1_000 + DsForeverScreen.CANCEL_GUARD_MS))
        // a failed open asks the watchdog, the TopWindow's too
        val f = source()
        val u = f.substringAfter("private fun openBottom(").substringBefore("\n    }\n")
        assertTrue(u.contains("bottom screen failed to open on display") && u.contains("reopenSoon("))
        assertTrue(f.substringAfter("private fun openTop(").substringBefore("\n    }\n").contains("reopenSoon("))
        // the watchdog's look goes on until a window is up, and stops while away / stopped / in single screen / Swap screens
        // (round 2: the look's own update asks again - twoScreens -> reopenSoon - only while a listed screen has no window up)
        val r = f.substringAfter("private val reopenLook: Runnable = Runnable {").substringBefore("\n    }\n")
        assertTrue(r.contains("update()") && r.contains("reopenTries++"))
        assertTrue(f.substringAfter("private fun reopenSoon(").substringBefore("\n    }\n").contains("reopenDelay(reopenTries, cancels.slow("))
    }

    @Test fun theWatchdogIsWantedOnlyWhileTheBottomScreenShouldBeUp() {
        assertTrue(DsForeverScreen.reopenWanted(running = true, hidden = false, single = false, swap = false, alive = false))
        assertFalse("a window up", DsForeverScreen.reopenWanted(true, false, false, false, alive = true))
        assertFalse("the launcher away", DsForeverScreen.reopenWanted(true, true, false, false, false))
        assertFalse("stopped", DsForeverScreen.reopenWanted(false, false, false, false, false))
        assertFalse("single screen mode", DsForeverScreen.reopenWanted(true, false, true, false, false))
        assertFalse("Swap screens", DsForeverScreen.reopenWanted(true, false, false, true, false))
    }

    @Test fun showAfter() {
        assertEquals(DsForeverScreen.SHOW_AGAIN_MS, DsForeverScreen.showAfter(fresh = true, gameRunning = true))
        assertEquals(250L, DsForeverScreen.SHOW_AGAIN_MS)
        assertEquals(DsForeverScreen.SHOW_AFTER_MS, DsForeverScreen.showAfter(fresh = false, gameRunning = true))
        assertEquals(DsForeverScreen.SHOW_AFTER_MS, DsForeverScreen.showAfter(fresh = true, gameRunning = false))
    }

    @Test fun onlyRealMemoryPressureLetsTheParkedHostGo() {
        // Android's levels (ComponentCallbacks2): 5 moderate, 10 low, 15 critical, 20 UI hidden (EVERY minimize), 40 background, 60, 80
        assertFalse("the routine minimize trim never", DsForeverScreen.parkReleaseAt(20, light8 = false))
        assertFalse(DsForeverScreen.parkReleaseAt(20, light8 = true))
        assertFalse(DsForeverScreen.parkReleaseAt(5, light8 = true))
        assertTrue(DsForeverScreen.parkReleaseAt(15, light8 = false))
        for (l in listOf(40, 60, 80)) assertTrue("level $l", DsForeverScreen.parkReleaseAt(l, light8 = false))
        assertFalse("running low: Light 8 only", DsForeverScreen.parkReleaseAt(10, light8 = false))
        assertTrue(DsForeverScreen.parkReleaseAt(10, light8 = true))
        assertTrue("onLowMemory", DsForeverScreen.parkReleaseAt(com.jrees.wowcompanion.MemWatch.LOW_MEMORY, light8 = false))
        // the trim is Android's own (registerComponentCallbacks), never MemWatch's self-relief (it calls its trimmers at Play / the world)
        val f = source()
        assertTrue(f.contains("registerComponentCallbacks(trims)"))
        assertTrue(f.substringAfter("private val trims = ").substringBefore("\n    }\n").contains("parkReleaseAt("))
        assertFalse(f.contains("MemWatch.onTrim(\"bottom-park\""))
        val rel = f.substringAfter("private fun release(").substringBefore("\n    }\n")
        assertTrue("a snapshot first, then the old teardown", rel.indexOf("keepSnapshot(") in 0 until rel.indexOf("shown?.close()"))
    }

    @Test fun swapScreensThreeQuickMinimizesNeverTripTheGuard() {
        // F6: only Android's closes (and an ask that never came) count, never our own close at a hide
        assertFalse(DsForeverScreen.swapStartCounts(closedByUs = true))
        assertTrue(DsForeverScreen.swapStartCounts(closedByUs = false))
        val starts = ArrayList<Long>()
        var now = 50_000L
        repeat(3) {                                    // minimize / return x3 within 15 s: our close, then a new ask
            if (DsForeverScreen.swapStartCounts(closedByUs = true)) starts.add(now)
            now += 2_000
            assertTrue("cycle $it", DsForeverScreen.startAllowed(starts, now))
        }
        // three Android closes in 15 s still give up (the #383 / #384 loop guard)
        repeat(3) { if (DsForeverScreen.swapStartCounts(closedByUs = false)) starts.add(now); now += 1_000 }
        assertFalse(DsForeverScreen.startAllowed(starts, now))
        val f = source()
        val open = f.substringAfter("private fun openActivity(").substringBefore("internal fun adopt(")
        assertFalse("an ask is no longer counted by itself", open.contains("starts.add(now)"))
        assertTrue(f.substringAfter("internal fun released(").substringBefore("internal fun gameToFront(").contains("starts.add("))
    }

    @Test fun aTwoScreenDeviceSeenOnceNeverTurnsSingleScreenOnByItself() {
        // F8: the second built-in screen seen once is saved (an app preference), read at the next process start
        assertTrue(DsForeverScreen.twoScreensSeen(process = false, saved = true))
        assertTrue(DsForeverScreen.twoScreensSeen(process = true, saved = false))
        assertFalse(DsForeverScreen.twoScreensSeen(process = false, saved = false))
        assertEquals("a crash / LMK restart with the bottom screen gone a moment: no single screen mode", SingleStep.NONE,
            DsForeverScreen.singleStep(false, MAIN, false, null, false, sawSecond = DsForeverScreen.twoScreensSeen(false, saved = true), oneForMs = 60_000L))
        val f = source()
        assertTrue(f.contains("const val TWO_SCREENS_SEEN = \"two_screens_seen\""))
        assertTrue("read at the start", f.substringAfter("fun start() {").substringBefore("fun stop() {").contains("sawSecond = twoScreensSeen(sawSecond, savedTwo)"))
        assertTrue("saved the first time a built-in one is picked", f.substringAfter("private fun update() {").substringBefore("/** our window is down").contains("saveTwoScreens("))
        // the park, the unpark and the bottom step never switch single screen mode
        for (fn in listOf("fun hide(", "private fun twoScreens(", "private fun release(", "internal fun lost(", "private val reopenLook"))
            assertFalse(fn, f.substringAfter(fn).substringBefore("\n    }\n").contains("SingleScreen.set("))
    }

    @Test fun theTopWindowIsKeptAndShownAgain() {
        // single screen mode's TopWindow (display 0) the same way: parked at a hide, unparked at the return
        assertEquals(BottomAct.PARK, DsForeverScreen.bottomStep(true, null, MAIN, Win(MAIN, alive = true, parked = false)))
        val f = source()
        val hide = f.substringAfter("fun hide(").substringBefore("\n    }\n")
        assertTrue(hide.contains("w.park("))
        val u = f.substringAfter("private fun update() {").substringBefore("/** our window is down")
        val single = u.substringAfter("if (SingleScreen.inUse) {").substringBefore("companionGone()\n")
        assertTrue("a parked top window is shown again", single.contains("else if (shown?.parked == true) { shown?.unpark(\"back\"); verifySoon() }"))
        assertTrue("a stale one is made again with a snapshot", single.indexOf("keepSnapshot(") in 0 until single.indexOf("shown = openTop()"))
        val top = source("TopWindow.kt")
        assertTrue("the decor GONE, never touchable, nothing kept awake", top.substringAfter("override fun park(").substringBefore("\n    }\n").let {
            it.contains("showDecor(false)") && it.contains("FLAG_NOT_TOUCHABLE") && it.contains("host?.park(") })
        assertTrue(top.substringAfter("override fun unpark(").substringBefore("\n    }\n").contains("host?.unpark("))
        assertTrue("its small windows too", source("SingleSub.kt").contains("fun park(on: Boolean)"))
    }

    @Test fun theParkedPresentationShowsNothingTakesNothingKeepsNothingAwake() {
        val p = source().substringAfter("private class HostPresentation(")
        assertTrue("F11: Dialog.isShowing is false while the decor is GONE", p.contains("override fun hostGone(): Boolean = !up"))
        val park = p.substringAfter("override fun park(").substringBefore("\n    }\n")
        assertTrue(park.contains("FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE"))
        assertTrue(park.contains("clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)"))
        assertTrue(park.contains("decorView?.visibility = View.GONE"))
        assertTrue(park.contains("if (typing) typer.focusable(false)"))
        assertTrue(park.contains("host?.park("))
        val unpark = p.substringAfter("override fun unpark(").substringBefore("\n    }\n")
        // (the review: hidden still while an activity of ours is over the display - openOnBottom)
        assertTrue(unpark.contains("decorView?.visibility = if (held) View.GONE else View.VISIBLE") && unpark.contains("clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)"))
        assertTrue("focusable again only for an open browser page", unpark.contains("if (pageSlot.up) typer.focusable(true)"))
        assertFalse("nothing requests focus", (park + unpark).contains("requestFocus"))
    }

    @Test fun hideParksAndStreamingCloses() {
        val f = source()
        assertTrue("MainActivity's hooks unchanged", f.contains("fun hideFor(activity: Activity) { current?.takeIf { it.context === activity }?.hide() }"))
        val stream = f.substringAfter("fun beginStream(activity: Activity) {").substringBefore("\n        }")
        assertTrue("Streaming's own Presentation: never a parked one beside it (two hosts)", stream.contains("prev?.hide(park = false)"))
        val hide = f.substringAfter("fun hide(").substringBefore("\n    }\n")
        assertTrue(hide.contains("BottomAct.PARK ->"))
        assertTrue("Swap screens' activity is closed as before", hide.contains("shown?.close(); shown = null; companionGone()"))
        assertTrue("onDisplayChanged is not empty", f.contains("override fun onDisplayChanged(displayId: Int) { openFails = 0; topFails = 0; main.removeCallbacks(displaySettle); main.postDelayed(displaySettle, DISPLAY_SETTLE_MS) }"))
        // no top-screen change from a park, an unpark or the step: no activity started, no task moved
        for (fn in listOf("fun hide(", "private fun twoScreens(", "private fun release(", "internal fun lost(", "private val reopenLook", "private val verifyRun"))
            for (bad in listOf("startActivity", "moveTaskToFront", "toFront(")) assertFalse("$fn: $bad", f.substringAfter(fn).substringBefore("\n    }\n").contains(bad))
        val p = f.substringAfter("private class HostPresentation(")
        for (fn in listOf("override fun park(", "override fun unpark("))
            for (bad in listOf("startActivity", "moveTaskToFront", "toFront(")) assertFalse("$fn: $bad", p.substringAfter(fn).substringBefore("\n    }\n").contains(bad))
    }

    @Test fun theVerifyLooksAgainAfterAnOpenOrAnUnpark() {
        assertEquals(700L, DsForeverScreen.VERIFY_MS)
        assertEquals(150L, DsForeverScreen.DISPLAY_SETTLE_MS)
        val f = source()
        val two = f.substringAfter("private fun twoScreens(").substringBefore("\n    }\n")
        assertTrue(two.contains("BottomAct.UNPARK -> { shown?.unpark(\"back\"); verifySoon() }"))
        val v = f.substringAfter("private val verifyRun: Runnable = Runnable {").substringBefore("\n    }\n")
        assertTrue("not up, or not drawn: made again", v.contains("!w.alive") && v.contains("!w.drawn"))
    }

    private fun source(name: String = "DsForeverScreen.kt"): String =
        listOf("src/main/java/com/jrees/wowcompanion/fork/$name", "dsforever/src/main/java/com/jrees/wowcompanion/fork/$name")
            .map { java.io.File(it) }.first { it.canRead() }.readText()
}
