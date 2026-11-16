package com.jrees.wowcompanion.fork

import com.jrees.wowcompanion.ScreenSwap
import com.jrees.wowcompanion.fork.DsForeverScreen.Companion.Bottom
import com.jrees.wowcompanion.fork.DsForeverScreen.Companion.Move
import com.jrees.wowcompanion.fork.DsForeverScreen.Companion.Screen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub #232 SWAP SCREENS (the user, 2026-11-14: build it, untested - no such device here): which display gets the game and which
 * DS Forever, off-device. Off must be exactly the Thor's arrangement (#196 included); On swaps them, display 0 taking DS Forever as
 * an activity (it takes no Presentation).
 */
class SwapScreensTest {
    private val MAIN = 0                                               // Display.DEFAULT_DISPLAY: the Thor's top screen
    private val thorBottom = Screen(4, builtIn = true, name = "Built-in Screen")
    private val tv = Screen(5, builtIn = false, name = "HDMI Screen")
    private val addOn = Screen(2, builtIn = false, name = "USB-C Screen")    // a dual-screen add-on on a USB-C port

    @After fun clean() = ScreenSwap.reset()

    // ---- Off: the Thor, unchanged

    @Test fun offTheGameStaysOnDisplay0() {
        assertEquals(MAIN, DsForeverScreen.gameScreen(listOf(thorBottom), swap = false))
        assertEquals(MAIN, DsForeverScreen.gameScreen(listOf(tv, thorBottom), swap = false, current = 4))
        assertEquals(MAIN, DsForeverScreen.gameScreen(emptyList(), swap = false))
    }

    @Test fun offDsForeverIsThePresentationPickScreenGives() {
        for (screens in listOf(listOf(thorBottom), listOf(tv, thorBottom), listOf(tv), emptyList(), listOf(thorBottom, thorBottom.copy(id = 3))))
            for (current in listOf(null, 4, 5)) {
                val want = DsForeverScreen.pickScreen(screens, MAIN, current)?.let { Bottom(it, activity = false) }
                assertEquals("$screens / $current", want, DsForeverScreen.bottomScreen(screens, MAIN, swap = false, current = current))
            }
        assertEquals(Bottom(4, false), DsForeverScreen.bottomScreen(listOf(tv, thorBottom), MAIN, swap = false, current = null))
    }

    @Test fun offResumeMovesAsBefore() {
        // the same answers as ResumeDisplayTest with no target given
        for (d in listOf(0, 4)) for (g in listOf(true, false)) for (since in listOf(1_000L, Long.MAX_VALUE))
            assertEquals(DsForeverScreen.decide(d, g, since), DsForeverScreen.decide(d, g, since, MAIN))
    }

    // ---- On: swapped

    @Test fun onTheGameGoesToTheSecondScreen() {
        assertEquals(4, DsForeverScreen.gameScreen(listOf(thorBottom), swap = true))
        assertEquals(2, DsForeverScreen.gameScreen(listOf(addOn), swap = true))          // the add-on is the only other screen: it is the one
    }

    /** #196 still holds: a TV never takes the game from the built-in second screen, listed first or plugged in later */
    @Test fun onATvNeverTakesTheGame() {
        assertEquals(4, DsForeverScreen.gameScreen(listOf(tv, thorBottom), swap = true))
        assertEquals(4, DsForeverScreen.gameScreen(listOf(tv, thorBottom), swap = true, current = 4))
    }

    @Test fun onWithASingleScreenNothingMoves() {
        assertEquals(MAIN, DsForeverScreen.gameScreen(emptyList(), swap = true))
        assertNull(DsForeverScreen.bottomScreen(emptyList(), MAIN, swap = true, current = null))
    }

    @Test fun onDsForeverGoesToDisplay0AsTheActivity() {
        assertEquals(Bottom(MAIN, activity = true), DsForeverScreen.bottomScreen(listOf(thorBottom), game = 4, swap = true, current = null))
        // a TV changes nothing (it is never DS Forever's either)
        assertEquals(Bottom(MAIN, activity = true), DsForeverScreen.bottomScreen(listOf(tv, thorBottom), game = 4, swap = true, current = MAIN))
        // display 0 listed as a presentation display (no device does this): a Presentation there after all
        assertEquals(Bottom(MAIN, activity = false), DsForeverScreen.bottomScreen(listOf(Screen(MAIN, true, "main"), thorBottom), 4, true, null))
    }

    /** the second screen gone (unplugged) while the game ran on it: it is back on display 0, DS Forever on whatever is left */
    @Test fun onSecondScreenGoneTheGameIsOnDisplay0Again() {
        assertNull(DsForeverScreen.bottomScreen(emptyList(), MAIN, swap = true, current = MAIN))
        assertEquals(Bottom(5, false), DsForeverScreen.bottomScreen(listOf(tv), MAIN, swap = true, current = null))
    }

    @Test fun onResumeMovesTheLauncherToTheSecondScreen() {
        // the icon tapped on display 0's home screen: no game -> started again there; a game running -> the same task moved
        assertEquals(Move.RELAUNCH, DsForeverScreen.decide(MAIN, gameRunning = false, sinceLastMoveMs = Long.MAX_VALUE, target = 4))
        assertEquals(Move.BRING_TO_TOP, DsForeverScreen.decide(MAIN, gameRunning = true, sinceLastMoveMs = Long.MAX_VALUE, target = 4))
        assertEquals(Move.NONE, DsForeverScreen.decide(4, gameRunning = true, sinceLastMoveMs = Long.MAX_VALUE, target = 4))
        assertEquals(Move.NONE, DsForeverScreen.decide(MAIN, gameRunning = true, sinceLastMoveMs = 1_000L, target = 4))   // just moved
    }

    // ---- the activity's start guard

    @Test fun theActivityIsNotStartedInALoop() {
        val now = 100_000L
        assertTrue(DsForeverScreen.startAllowed(emptyList(), now))
        assertTrue(DsForeverScreen.startAllowed(listOf(now - 1_000, now - 500), now))
        assertFalse(DsForeverScreen.startAllowed(listOf(now - 3_000, now - 1_000, now - 500), now))
        // old starts do not count
        assertTrue(DsForeverScreen.startAllowed(listOf(now - DsForeverScreen.START_GUARD_MS, now - 20_000, now - 500), now))
    }

    // ---- the review's fixes (#232 review, 2026-11-14)

    /**
     * the tap-pad's taps (and the sign-in's field taps) land on the same game pixel with Swap screens on: TapBridge makes a cell's
     * pixels in display 0's space (the Thor: 1920 x 1080), InputSink divides by ITS size - display 0's again, never the game's own
     * screen (the Thor's other panel is 1240 x 1080: the middle of the game would have gone to 0.77 of its width)
     */
    @Test fun tapsKeepDisplay0sSpaceWithSwapOn() {
        assertEquals(MAIN, DsForeverScreen.TAP_SPACE_DISPLAY)
        val panels = mapOf(MAIN to (1920 to 1080), 4 to (1240 to 1080))
        val pw = 960.0                                                  // the game's own width (the pad's pw)
        for (gx in listOf(0.0, 123.5, 480.0, 959.0)) {
            val tapX = gx * panels.getValue(MAIN).first / pw            // TapBridge.gamePoint
            val sw = panels.getValue(DsForeverScreen.TAP_SPACE_DISPLAY).first
            assertEquals("game x $gx", gx / pw, tapX / sw, 1e-9)        // InputSink.play: the fraction the X server gets
        }
        // the game's own screen's size would have sent the middle of the game to 0.77 of its width
        assertEquals(0.774, 480.0 * 1920 / pw / panels.getValue(4).first, 1e-3)
        // and the launcher's start sizes InputSink from it, whatever Swap screens says
        val start = source().substringAfter("fun start() {").substringBefore("fun stop() {")
        assertTrue(start.contains("dm?.getDisplay(TAP_SPACE_DISPLAY)?.mode"))
        assertFalse("never the game's own screen", start.contains("getDisplay(gameDisplay())") || start.contains("getDisplay(sizeOf)"))
    }

    /** the game's task comes to the front only while DS Forever runs and shows: never pulls the launcher back over a Home press */
    @Test fun theGameIsBroughtForwardOnlyWhileTheLauncherShows() {
        assertTrue(DsForeverScreen.frontWanted(running = true, hidden = false, launcherFinishing = false))
        assertFalse(DsForeverScreen.frontWanted(running = true, hidden = true, launcherFinishing = false))      // Home / pause: hide()
        assertFalse(DsForeverScreen.frontWanted(running = true, hidden = false, launcherFinishing = true))      // the restart, a close
        assertFalse(DsForeverScreen.frontWanted(running = false, hidden = false, launcherFinishing = false))   // stopped
        val fn = source().substringAfter("internal fun gameToFront(").substringBefore("private val restartHook")
        assertTrue(fn.contains("frontWanted(running, hidden, a.isFinishing)"))
    }

    /** Android's own close of the bottom activity asks for it again (the start guard stops a loop); our close does not */
    @Test fun anAndroidCloseIsAskedAgain() {
        val fn = source().substringAfter("internal fun released(").substringBefore("internal fun gameToFront(")
        val androids = fn.substringAfter("if (!w.closed) {")
        assertTrue(androids.contains("main.post { update() }"))
        assertTrue(source().substringAfter("private fun openActivity(").substringBefore("internal fun adopt(").contains("startAllowed(starts, now)"))
    }

    /** a Back that reaches the bottom activity (typing) never closes it, on Android 16 too (no onBackPressed there for API 36 apps) */
    @Test fun backNeverClosesTheBottomActivity() {
        val a = source("CompanionScreenActivity.kt")
        assertTrue(a.contains("override fun onBackPressed() {}"))
        assertTrue(a.contains("registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { }"))
    }

    private fun source(name: String = "DsForeverScreen.kt"): String =
        listOf("src/main/java/com/jrees/wowcompanion/fork/$name", "dsforever/src/main/java/com/jrees/wowcompanion/fork/$name")
            .map { java.io.File(it) }.first { it.canRead() }.readText()

    private fun manifest(): String =
        listOf("src/main/AndroidManifest.xml", "dsforever/src/main/AndroidManifest.xml").map { java.io.File(it) }.first { it.canRead() }.readText()

    // ---- #383 / #384 (a player on a Retroid Pocket Nova + Dual Screen Add-on, 1.0.32): the restart loop, then the input ANRs

    /**
     * "the bottom screen's activity restarts itself until the guard gives up": the game's task came forward with AppTask.moveToFront,
     * Recents' route, which puts display 0's home screen in front first; Android then trims a Recents-excluded task behind Home on
     * display 0 - the bottom activity closed, was opened again and asked again. Neither file may use AppTask's move any more
     */
    @Test fun theGameComesForwardWithoutRecentsRoute() {
        for (f in listOf("DsForeverScreen.kt", "CompanionScreenActivity.kt")) {
            val code = source(f).lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/**") }
                .joinToString("\n")
            assertFalse("$f: no AppTask", code.contains("appTasks"))
            assertFalse("$f: no AppTask.moveToFront", code.contains(".moveToFront()"))
        }
        val to = source().substringAfter("internal fun toFront(").substringBefore("fun begin(")
        assertTrue(to.contains("am.moveTaskToFront(taskId, android.app.ActivityManager.MOVE_TASK_NO_USER_ACTION)"))
        assertTrue(source().substringAfter("internal fun gameToFront(").substringBefore("private val restartHook").contains("toFront(a, a.taskId,"))
        assertTrue(source("CompanionScreenActivity.kt").substringAfter("private fun typingChanged(").substringBefore("fun board(")
            .contains("DsForeverScreen.toFront(this, taskId,"))
        // moveTaskToFront needs REORDER_TASKS (a normal permission: granted with the install, no prompt)
        assertTrue(manifest().contains("<uses-permission android:name=\"android.permission.REORDER_TASKS\" />"))
    }

    /** the once-a-second gap: counted by DsForeverScreen (every bottom activity is a new one: its own field never held anything back) */
    @Test fun theGapOutlivesTheBottomActivity() {
        val gap = DsForeverScreen.FRONT_GAP_MS
        assertEquals(0L, DsForeverScreen.frontWait(100_000L, Long.MIN_VALUE / 2))          // never moved: at once
        assertEquals(gap, DsForeverScreen.frontWait(100_000L, 100_000L))
        assertEquals(400L, DsForeverScreen.frontWait(100_600L, 100_000L))
        assertEquals(0L, DsForeverScreen.frontWait(100_000L + gap, 100_000L))
        assertEquals(0L, DsForeverScreen.frontWait(500_000L, 100_000L))
        assertEquals("a clock that went back waits one gap, no more", gap, DsForeverScreen.frontWait(99_000L, 100_000L))
        // the reporter's three rounds (13.293, 13.931, 14.102): a new activity each time, one move only
        var last = Long.MIN_VALUE / 2
        var moves = 0
        for (t in listOf(13_293L, 13_931L, 14_102L)) if (DsForeverScreen.frontWait(t, last) == 0L) { last = t; moves++ }
        assertEquals(1, moves)
        assertFalse(source("CompanionScreenActivity.kt").contains("frontAt"))
        val fn = source().substringAfter("internal fun gameToFront(").substringBefore("private val restartHook")
        assertTrue(fn.contains("frontWait(now, frontAt)"))
        assertTrue(fn.contains("private var frontAt = Long.MIN_VALUE / 2"))
    }

    /**
     * "input ANRs once the loop is stopped" (no focused window): display 0 on top with only a not-focusable window holds the keys.
     * An ask inside the gap is not dropped (it runs after it while the bottom activity still holds them), and the bottom activity asks
     * whenever it becomes Android's top resumed activity - display 0 took the keys - unless a text field types there
     */
    @Test fun display0NeverKeepsTheKeys() {
        val fn = source().substringAfter("internal fun gameToFront(").substringBefore("private val restartHook")
        val later = fn.substringAfter("if (wait > 0L) {").substringBefore("frontAt = now")
        assertTrue(later.contains("main.postDelayed("))
        assertTrue(later.contains("(shown as? AskedActivity)?.activity?.holdsKeys == true"))
        assertTrue("one ask waits at a time", later.contains("if (!frontLater) {"))
        val a = source("CompanionScreenActivity.kt")
        assertTrue(a.contains("val holdsKeys: Boolean get() = host != null && topResumed && !typer.typing && !isFinishing"))
        val top = a.substringAfter("override fun onTopResumedActivityChanged(").substringBefore("override fun onStop()")
        assertTrue(top.contains("topResumed = isTopResumedActivity"))
        assertTrue(top.contains("if (holdsKeys) screen?.gameToFront("))
        assertTrue(a.substringAfter("override fun onResume()").substringBefore("override fun onTopResumedActivityChanged(")
            .contains("if (host != null && !typer.typing) screen?.gameToFront(\"bottom screen resumed\")"))
    }

    /** Swap screens Off (the Thor): the Presentation never moves a task; only the Swap screens activity asks */
    @Test fun offNothingMovesATask() {
        val presentation = source().substringAfter("private class HostPresentation(")
        assertFalse(presentation.contains("toFront("))
        assertFalse(presentation.contains("gameToFront("))
        val outside = source().substringBefore("internal fun gameToFront(") + source().substringAfter("private val restartHook")
        assertFalse("gameToFront is asked only by the bottom activity (and its own wait)", outside.contains("gameToFront("))
        assertFalse(outside.substringBefore("internal fun toFront(").contains("toFront("))
    }

    // ---- the Settings row's Restart (ScreenSwap)

    @Test fun restartRowOnlyWhenTheChoiceIsNotTheOneInUse() {
        val p = ScreenSwap::pending
        assertEquals(ScreenSwap.Pending.NONE, p(false, false, false, true))
        assertEquals(ScreenSwap.Pending.NONE, p(true, true, true, true))
        assertEquals(ScreenSwap.Pending.RESTART, p(true, false, false, true))
        assertEquals(ScreenSwap.Pending.RESTART, p(false, true, false, true))
        // the game up: a restart would end it, so only "after the game closes"
        assertEquals(ScreenSwap.Pending.AFTER_GAME, p(true, false, true, true))
        // no launcher (nothing latched, no restart): nothing
        assertEquals(ScreenSwap.Pending.NONE, p(true, null, false, true))
        assertEquals(ScreenSwap.Pending.NONE, p(true, false, false, false))
    }

    @Test fun theChoiceAppliesFromAStart() {
        assertNull(ScreenSwap.inUseNow)
        ScreenSwap.take(false)
        assertEquals(false, ScreenSwap.inUseNow)
        // saved On later: still Off in this run until the restart takes it
        assertEquals(ScreenSwap.Pending.RESTART, ScreenSwap.pending(true, ScreenSwap.inUseNow, false, true))
        ScreenSwap.take(true)
        assertEquals(ScreenSwap.Pending.NONE, ScreenSwap.pending(true, ScreenSwap.inUseNow, false, true))
    }

    // ---- #384 recovery (the user, 2026-11-16: "make sure there IS a safe recovery path in the app ... if the UI is stuck")

    private class Prefs(val m: HashMap<String, Any?> = HashMap()) : android.content.SharedPreferences {
        override fun getAll(): MutableMap<String, *> = m
        override fun getString(k: String?, d: String?) = m[k] as? String ?: d
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(k: String?, d: MutableSet<String>?) = m[k] as? MutableSet<String> ?: d
        override fun getInt(k: String?, d: Int) = m[k] as? Int ?: d
        override fun getLong(k: String?, d: Long) = m[k] as? Long ?: d
        override fun getFloat(k: String?, d: Float) = m[k] as? Float ?: d
        override fun getBoolean(k: String?, d: Boolean) = m[k] as? Boolean ?: d
        override fun contains(k: String?) = m.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
            val put = HashMap<String, Any?>(); val gone = HashSet<String>()
            override fun putString(k: String, v: String?) = apply { put[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = apply { put[k] = v }
            override fun putInt(k: String, v: Int) = apply { put[k] = v }
            override fun putLong(k: String, v: Long) = apply { put[k] = v }
            override fun putFloat(k: String, v: Float) = apply { put[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { put[k] = v }
            override fun remove(k: String) = apply { gone.add(k) }
            override fun clear() = apply { gone.addAll(m.keys) }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { for (k in gone) m.remove(k); m.putAll(put) }
        }
    }

    private fun fails(p: Prefs) = p.getInt(ScreenSwap.FAILS_PREF, 0)
    private fun saved(p: Prefs) = p.getBoolean(ScreenSwap.PREF, false)
    /** a new start of DS Forever (a new process): nothing latched */
    private fun newStart(p: Prefs): String? { ScreenSwap.reset(); return ScreenSwap.latch(p) }

    /**
     * the bottom screen never settles (it keeps closing, never comes, display 0 keeps the controller, a crash, a force stop): two such
     * starts in a row and the third is Off - this run's choice and the saved one (the row shows Off; On again only by hand)
     */
    @Test fun twoUnsettledStartsThenTheNextStartIsOff() {
        assertEquals(2, ScreenSwap.MAX_FAILS)
        assertTrue(ScreenSwap.keepOn(0)); assertTrue(ScreenSwap.keepOn(1)); assertFalse(ScreenSwap.keepOn(2)); assertFalse(ScreenSwap.keepOn(5))
        val p = Prefs(hashMapOf(ScreenSwap.PREF to true))
        assertNull(newStart(p)); assertEquals(true, ScreenSwap.inUseNow)
        assertTrue(ScreenSwap.tryStart(p)); assertEquals(1, fails(p))               // counted before the activity comes
        assertNull(newStart(p)); assertEquals(true, ScreenSwap.inUseNow)
        assertTrue(ScreenSwap.tryStart(p)); assertEquals(2, fails(p))
        val why = newStart(p)
        assertEquals("the bottom screen did not settle on the last 2 starts", why)
        assertEquals(false, ScreenSwap.inUseNow)
        assertFalse("saved Off too", saved(p))
        assertEquals("the count starts over", 0, fails(p))
        assertEquals(why, ScreenSwap.fellBack)
        assertTrue(ScreenSwap.line(saved(p)).endsWith("; turned Off: $why"))
        // and Off stays Off: no count, nothing turns it On again
        assertNull(newStart(p)); assertEquals(false, ScreenSwap.inUseNow)
    }

    /** a bottom screen that settled clears the count: a device where Swap screens works never falls back */
    @Test fun aSettledStartClearsTheCount() {
        val p = Prefs(hashMapOf(ScreenSwap.PREF to true))
        repeat(5) {
            assertNull(newStart(p)); assertEquals(true, ScreenSwap.inUseNow)
            assertTrue(ScreenSwap.tryStart(p)); assertEquals(1, fails(p))
            ScreenSwap.settled(p); assertEquals(0, fails(p))
        }
        // one unsettled start between settled ones changes nothing either
        newStart(p); ScreenSwap.tryStart(p); newStart(p); ScreenSwap.tryStart(p); ScreenSwap.settled(p)
        assertNull(newStart(p)); assertEquals(true, ScreenSwap.inUseNow)
    }

    /** the process lived on (no new latch): the ask itself says no at the count, and counts nothing */
    @Test fun theAskSaysNoAtTheCount() {
        val p = Prefs(hashMapOf(ScreenSwap.PREF to true, ScreenSwap.FAILS_PREF to 2))
        assertFalse(ScreenSwap.tryStart(p))
        assertEquals(2, fails(p))
    }

    /** off() saves Off at once; the run keeps the arrangement it latched (the Settings row then shows Restart) */
    @Test fun offSavesOffThisRunKeepsItsArrangement() {
        val p = Prefs(hashMapOf(ScreenSwap.PREF to true, ScreenSwap.FAILS_PREF to 1))
        ScreenSwap.take(true)
        ScreenSwap.off(p, "the bottom screen kept closing")
        assertFalse(saved(p)); assertEquals(0, fails(p))
        assertEquals(true, ScreenSwap.inUseNow)
        assertEquals(ScreenSwap.Pending.RESTART, ScreenSwap.pending(saved(p), ScreenSwap.inUseNow, false, true))
        assertEquals("the bottom screen kept closing", ScreenSwap.fellBack)
    }

    /** a choice by hand gives On its tries again (the count is removed with it) */
    @Test fun aChoiceByHandStartsTheCountOver() {
        val p = Prefs(hashMapOf(ScreenSwap.PREF to false, ScreenSwap.FAILS_PREF to 2))
        try {
            com.jrees.wowcompanion.Settings.startForTest(p)
            com.jrees.wowcompanion.Settings.swapScreens = true
            assertTrue(saved(p)); assertFalse(p.contains(ScreenSwap.FAILS_PREF))
            assertNull(newStart(p)); assertEquals(true, ScreenSwap.inUseNow)
        } finally { com.jrees.wowcompanion.Settings.startForTest(null) }
    }

    /** how DsForeverScreen wires it: the count, the settle, the give-up's Off, the restart only with no game, the by-hand extra */
    @Test fun theLauncherCountsSettlesAndFallsBack() {
        val open = source().substringAfter("private fun openActivity(").substringBefore("internal fun adopt(")
        val first = open.substringAfter("if (!tried) {").substringBefore("val now = ")
        assertTrue("counted once per DS Forever", first.contains("tried = true") && first.contains("if (!ScreenSwap.tryStart(context)) {"))
        assertTrue(first.contains("swapOff(") && first.contains("return null"))
        assertTrue("before the activity is started", open.indexOf("ScreenSwap.tryStart(context)") < open.indexOf("context.startActivity("))
        val guard = open.substringAfter("if (!startAllowed(starts, now)) {").substringBefore("starts.add(now)")
        assertTrue("the start guard's give-up turns it Off", guard.contains("swapOff(\"the bottom screen kept closing\")"))
        val adopt = source().substringAfter("internal fun adopt(").substringBefore("private fun settleCheck(")
        assertTrue(adopt.contains("main.postDelayed({ settleCheck(w, a) }, ScreenSwap.SETTLE_MS)"))
        val settle = source().substringAfter("private fun settleCheck(").substringBefore("private fun swapOff(")
        assertTrue("the same activity, still up", settle.contains("shown !== w || w.closed || w.activity !== a"))
        assertTrue("never while display 0 holds the keys", settle.indexOf("if (a.holdsKeys)") in 0 until settle.indexOf("ScreenSwap.settled(context)"))
        val off = source().substringAfter("private fun swapOff(").substringBefore("internal fun released(")
        assertTrue(off.contains("ScreenSwap.off(context, why)"))
        assertTrue("restarts only the launcher's own, with no game", off.contains("if (!gameOn && ScreenSwap.restart === restartHook) restartHook()"))
        assertTrue(source().substringAfter("private fun restartForSwap()").substringBefore("a.finishAndRemoveTask()").contains("if (gameOn)"))
        val top = source().substringAfter("fun moveToTop(activity: Activity, gameRunning: Boolean): Boolean {")
        val extra = top.substringBefore("val target = gameTarget(activity, d)")
        assertTrue("the extra before this run latches", extra.contains("getBooleanExtra(ScreenSwap.EXTRA_OFF, false) == true"))
        assertTrue("and gone, so a restart's copy never repeats it", extra.contains("activity.intent.removeExtra(ScreenSwap.EXTRA_OFF)"))
        assertTrue(extra.contains("ScreenSwap.off(activity,"))
        assertEquals("dsforever_swap_off", ScreenSwap.EXTRA_OFF)
        assertEquals(DsForeverScreen.START_GUARD_MS, ScreenSwap.SETTLE_MS)
    }

    /**
     * #384 review: a run counts ONCE, however many launcher activities (DsForeverScreen) it has - a move to the other screen can
     * rebuild the launcher's activity (Home + reopen from the other screen's icon), and each new one counted again: a couple within
     * 15 s turned a working Swap screens Off mid-run. The Restart ([ScreenSwap.take]) is a new run and counts again
     */
    @Test fun aRunIsCountedOnce() {
        val p = Prefs(hashMapOf(ScreenSwap.PREF to true))
        assertNull(newStart(p))
        repeat(4) { assertTrue("the same run asks again: allowed, not counted", ScreenSwap.tryStart(p)) }
        assertEquals(1, fails(p))
        assertNull("the next start is still On", newStart(p)); assertEquals(true, ScreenSwap.inUseNow)
        repeat(3) { assertTrue(ScreenSwap.tryStart(p)) }
        assertEquals(2, fails(p))
        // the Restart: a new run in the same process; MAX_FAILS unsettled already, so its ask says no (the caller turns it Off)
        ScreenSwap.take(true)
        assertFalse(ScreenSwap.tryStart(p)); assertEquals(2, fails(p))
        // a settled run, then a Restart: counted anew from 0
        ScreenSwap.settled(p); ScreenSwap.take(true)
        assertTrue(ScreenSwap.tryStart(p)); assertTrue(ScreenSwap.tryStart(p)); assertEquals(1, fails(p))
    }

    /**
     * #384 review: the Restart (the Settings button and the recovery's own) hands over. Android destroys the old launcher activity
     * only after the new one is up, so the new one's begin() found the old DS Forever still current and made none, and the old one's
     * end() / stop then left neither screen with DS Forever. Now the old one stops and lets go first, and the launcher activity's
     * pause / stop / leave hint hide only its own
     */
    @Test fun theRestartHandsOver() {
        val restart = source().substringAfter("private fun restartForSwap()").substringBefore("a.finishAndRemoveTask()")
        assertTrue(restart.contains("stop()") && restart.contains("if (current === this) current = null"))
        assertTrue("never while the game runs, before anything stops", restart.indexOf("if (gameOn)") in 0 until restart.indexOf("stop()"))
        assertTrue(source().contains("fun hideFor(activity: Activity) { current?.takeIf { it.context === activity }?.hide() }"))
        assertTrue(source().substringAfter("fun begin(activity: Activity) {").substringBefore("fun end(").contains("if (current == null)"))
        val main = listOf("../app/src/main/java/app/gamenative/MainActivity.kt", "app/src/main/java/app/gamenative/MainActivity.kt")
            .map { java.io.File(it) }.first { it.canRead() }.readText()
        assertFalse("no unbound hide left", main.contains("DsForeverScreen.current?.hide()"))
        for (cb in listOf("override fun onUserLeaveHint()", "override fun onStop()", "override fun onPause()"))
            assertTrue(cb, main.substringAfter(cb).substringBefore("super.").contains("DsForeverScreen.hideFor(this)"))
    }

    // ---- the problem report

    @Test fun theReportNamesTheDisplays() {
        val thor = ScreenSwap.placement(MAIN, 4, asActivity = false, seen = "4:in:Built-in Screen")
        assertEquals("game on display 0, DS Forever on display 4 (presentation); presentation displays 4:in:Built-in Screen", thor)
        assertTrue(ScreenSwap.note(thor))
        assertFalse("the same placement is noted once", ScreenSwap.note(thor))
        ScreenSwap.take(false); ScreenSwap.offered = true
        assertEquals("Swap screens: saved off, in use off; $thor", ScreenSwap.line(false))
        val swapped = ScreenSwap.placement(4, MAIN, asActivity = true, seen = "4:in:Built-in Screen, 5:ext:HDMI Screen")
        assertTrue(ScreenSwap.note(swapped))
        ScreenSwap.take(true)
        assertEquals("Swap screens: saved on, in use on; game on display 4, DS Forever on display 0 (activity); " +
            "presentation displays 4:in:Built-in Screen, 5:ext:HDMI Screen", ScreenSwap.line(true))
        ScreenSwap.offered = false
        assertTrue(ScreenSwap.line(true).contains("(no second screen)"))
        assertEquals("game on display 0, DS Forever on no screen; presentation displays none", ScreenSwap.placement(MAIN, null, false, ""))
    }
}
