package com.jrees.wowcompanion.fork

import com.jrees.wowcompanion.SingleScreen
import com.jrees.wowcompanion.fork.DsForeverScreen.Companion.KeysStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SINGLE SCREEN MODE: THE CONTROLLER AFTER A TOUCH ON THE BOTTOM SCREEN (spec 4.2; the open ruling R1, built default A "The game keeps
 * the controller"): Android's launcher on the bottom screen takes the keys at a palm's touch; the game's task comes back to the front a
 * moment later (once a second at most, the #383 / #384 move: moveTaskToFront, never AppTask's, never a startActivity), never on two
 * screens, never while a field of ours types or an activity of ours is up on the other screen. R1 B = the hint only.
 */
class KeysBackTest {
    @Test fun onlyInSingleScreenModeWithTheGameUp() {
        assertTrue(DsForeverScreen.keysBackWanted(single = true, gameOn = true, running = true, hidden = false, finishing = false,
            typing = false, keysPaused = false))
        assertFalse("two screens: never (the Presentation never took the keys)",
            DsForeverScreen.keysBackWanted(false, true, true, false, false, false, false))
        assertFalse("no game", DsForeverScreen.keysBackWanted(true, false, true, false, false, false, false))
        assertFalse("stopped", DsForeverScreen.keysBackWanted(true, true, false, false, false, false, false))
        assertFalse("the launcher away", DsForeverScreen.keysBackWanted(true, true, true, true, false, false, false))
        assertFalse("finishing", DsForeverScreen.keysBackWanted(true, true, true, false, true, false, false))
        assertFalse("a field of ours types: the top window holds the keys on purpose", DsForeverScreen.keysBackWanted(true, true, true, false, false, true, false))
        assertFalse("an activity of ours on the other screen", DsForeverScreen.keysBackWanted(true, true, true, false, false, false, true))
    }

    @Test fun r1AMovesLaterR1BHintsAtOnce() {
        assertEquals(KeysStep.MOVE_LATER, DsForeverScreen.keysStep(true))
        assertEquals(KeysStep.HINT_NOW, DsForeverScreen.keysStep(false))
        assertEquals("the built default", KeysStep.MOVE_LATER, DsForeverScreen.keysStep())
        assertEquals(400L, SingleScreen.KEYS_BACK_MS)
    }

    @Test fun theOneSecondGapCoalesces() {
        // a palm that keeps touching: one move a second at most (the #383 / #384 gap, shared with Swap screens)
        var last = Long.MIN_VALUE / 2
        var moves = 0
        for (t in listOf(10_000L, 10_200L, 10_450L, 10_990L, 11_001L)) if (DsForeverScreen.frontWait(t, last) == 0L) { last = t; moves++ }
        assertEquals(2, moves)
    }

    @Test fun theRoutesInTheSources() {
        val src = source()
        val keys = src.substringAfter("internal fun keysTop(top: Boolean) {").substringBefore("/** a switch, a hide, a stop")
        assertFalse("never a startActivity (it pauses the launcher: onPause hides DS Forever)", keys.contains("startActivity"))
        assertFalse("never AppTask's move", keys.contains("moveToFront()") || keys.contains("appTasks"))
        assertTrue("the #383 / #384 move", keys.contains("gameToFront(\"one screen: the other screen took the controller\")"))
        assertTrue(keys.contains("main.postDelayed(keysBackRun, SingleScreen.KEYS_BACK_MS)"))
        assertTrue(keys.contains("SingleScreen.KEYS_BACK_MS + SingleScreen.KEYS_STUCK_MS"))
        assertTrue("the plate when moved", keys.contains("hint(SingleScreen.CONTROLLER_BACK, SingleScreen.HINT_MS)"))
        assertTrue("the plate when it stays", keys.contains("hint(SingleScreen.TAP_FOR_KEYS)"))
        assertTrue("back: the stuck plate goes, \"Controller back\" stays its time", keys.contains("if (keysHintStuck) { keysHintStuck = false; (shown as? TopWindow)?.hint(null) }"))
        // the ask inside the gap runs after it while the other screen still has the keys
        val gap = src.substringAfter("internal fun gameToFront(").substringBefore("frontAt = now")
        assertTrue(gap.contains("|| singleKeysAway()) gameToFront(\"\$why, after the gap\")"))
        // MainActivity's one override; only the launcher's own DS Forever hears it
        val main = listOf("../app/src/main/java/app/gamenative/MainActivity.kt", "app/src/main/java/app/gamenative/MainActivity.kt")
            .map { java.io.File(it) }.first { it.canRead() }.readText()
        assertTrue(main.contains("override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {"))
        assertTrue(main.contains("com.jrees.wowcompanion.fork.DsForeverScreen.topResumedFor(this, isTopResumedActivity)"))
        assertTrue(src.contains("fun topResumedFor(activity: Activity, top: Boolean) { current?.takeIf { it.context === activity }?.keysTop(top) }"))
        // a hide / stop / switch cancels what waits
        for (f in listOf("fun hide() {", "fun stop() {")) assertTrue(f, src.substringAfter(f).substringBefore("\n    }").contains("keysCancel()"))
        // no new permission: REORDER_TASKS was declared already (its comment names its second user)
        val manifest = listOf("src/main/AndroidManifest.xml", "dsforever/src/main/AndroidManifest.xml").map { java.io.File(it) }.first { it.canRead() }.readText()
        assertTrue(manifest.contains("in single screen mode when the bottom"))
        val perms = Regex("<uses-permission android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toList()
        assertEquals(listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.REORDER_TASKS",
            "android.permission.WAKE_LOCK"), perms)
    }

    /**
     * THE REVIEW (2026-11-25, "the keys hint can't be followed under a panel"): our windows are never focusable, so a tap on them never
     * moves Android's keys back (WMS moves a display to the top only for a window that takes keys), and a panel covers all of display 0.
     * While the other screen has the keys, a touch DOWN on the top window or a small one is the player's wish to play here: the game's
     * task to the front (the one-second gap coalesces)
     */
    @Test fun aTouchOnOurWindowsTakesTheKeysBack() {
        val src = source()
        val hook = src.substringAfter("internal fun ourTouch() {").substringBefore("\n    }")
        assertTrue(hook.contains("if (!singleKeysAway()) return") && hook.contains("gameToFront(\"one screen: a touch on our window\")"))
        val top = source("TopWindow.kt").substringAfter("override fun dispatchTouchEvent(ev: MotionEvent): Boolean {").substringBefore("\n    }")
        assertTrue(top.contains("if (ev.actionMasked == MotionEvent.ACTION_DOWN) screen.ourTouch()"))
        val sub = source("SingleSub.kt")
        assertTrue(sub.contains("override fun dispatchTouchEvent(ev: MotionEvent): Boolean {") &&
            sub.contains("if (ev.actionMasked == MotionEvent.ACTION_DOWN) onDown()"))
        assertTrue("the top window's small ones carry the hook", source("TopWindow.kt").contains("SubWindow(activity, id) { screen.ourTouch() }"))
        // the plate's priorities: a timed note over the stuck words never ends them (SingleScreen.Plate)
        assertTrue(source("TopWindow.kt").contains("private val plate = SingleScreen.Plate()"))
    }

    private fun source(name: String = "DsForeverScreen.kt"): String =
        listOf("src/main/java/com/jrees/wowcompanion/fork/$name", "dsforever/src/main/java/com/jrees/wowcompanion/fork/$name")
            .map { java.io.File(it) }.first { it.canRead() }.readText()
}
