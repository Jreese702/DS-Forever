package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE HOST AWAY AND BACK (the bottom screen comes straight back, 2026-11-25): a parked CompanionHost runs its onStop's body - the
 * wheels and the sign-in prompt CLOSED (the user's pick Q1: "closed on return"), voice off, the pad's reader shut - but never shows the
 * game's own UI on the top screen (GameUi.onVisible(false): nobody can see the game then, and a park must not tap it open and shut
 * again). Its reader pauses, its repeating ticks stop, every tap waits (GameInput.away, GameUi's hold), the indoor maps may build
 * (#568 / #573). The return gives it all back and runs onStart's body. A rebuilt host restores the snapshot (the room by the adopt
 * route only: no entry tap). The app's own MainActivity is unchanged.
 */
class BottomHostParkTest {
    private val src = listOf(File("../companion/app/src/main/java/com/jrees/wowcompanion"), File("companion/app/src/main/java/com/jrees/wowcompanion"))
        .first { it.isDirectory }
    private val host = File(src, "CompanionHost.kt").readText()
    private fun fn(start: String) = host.substringAfter(start).substringBefore("\n    }\n")

    @Test fun aParkIsTheStopWithoutTheGamesUi() {
        val park = fn("fun park(why: String) {")
        assertTrue(park.contains("onStop(gameHidden = true)"))
        assertFalse("never GameUi.onVisible(false) on a park", park.contains("GameUi.onVisible("))
        assertTrue("#568 / #573: the indoor maps may build", park.contains("IndoorSchedulerStep.companionGone()"))
        assertTrue(park.contains("tail?.pause()"))
        assertTrue("no taps while parked", park.contains("GameInput.away = true") && park.contains("GameUi.holdTaps(Long.MAX_VALUE)"))
        assertTrue("the exit guard's reader goes", park.contains("guardOff()"))
        assertTrue(park.contains("DiagLog.event(\"app\", \"bottom screen kept"))
        val stop = fn("private fun stopBody(gameHidden: Boolean) {")         // (round 2: onStop runs it, the park's hold let go after)
        assertTrue("the game's UI only on a real stop", stop.contains("if (!gameHidden) GameUi.onVisible(false)"))
        // Q1: the wheels and the sign-in prompt close (a wheel holds the controller in the game)
        for (w in listOf("loginPrompt.close()", "radialView.shut()", "pauseView.shut()", "targetRadial.shut()", "DirectPad.shutdown()"))
            assertTrue(w, stop.contains(w))
    }

    @Test fun theReturnGivesItAllBack() {
        val unpark = fn("fun unpark(why: String) {")
        assertTrue(unpark.contains("GameInput.away = false"))
        assertTrue(unpark.contains("GameUi.holdTaps(tapHoldBefore)"))
        assertTrue(unpark.contains("IndoorSchedulerStep.companionUp(this)"))
        assertTrue("a park is no silence", unpark.contains("silence.resumed("))
        assertTrue(unpark.contains("tail?.resume()"))
        assertTrue(unpark.contains("onStart()"))
        assertTrue("the ticks that ran", unpark.contains("for (r in parkTicks) { ui.removeCallbacks(r); ui.post(r) }"))
        assertTrue(unpark.contains("BottomSync.back("))
        assertTrue(unpark.contains("DiagLog.event(\"app\", \"bottom screen shown again"))
    }

    @Test fun everyRepeatingTickIsStoppedWhileParked() {
        val list = host.substringAfter("private fun repeatingTicks(): List<Runnable> =").substringBefore("\n\n")
        for (t in listOf("secondTick", "guardTick", "silenceTick", "padOwnerTick", "castAskTick", "castTabTick", "keyAskTick", "signInTick",
            "rootCheck", "recTick", "psStepTick", "talkTick", "playTick", "singlePoll"))
            assertTrue(t, list.contains(t))
        assertTrue(fn("fun park(why: String) {").contains("repeatingTicks().filter { ui.hasCallbacks(it) }"))
        assertTrue("the once-a-second loop is named", host.contains("ui.post(secondTick)"))
        assertFalse(host.contains("        ui.post(object : Runnable {\n            override fun run() {\n                // Home holds the same map"))
    }

    @Test fun tapsWaitEverywhereWhileParked() {
        val gi = File(src, "GameInput.kt").readText()
        assertTrue(gi.contains("fun live(now: Long = System.currentTimeMillis()) = !away && gate.live(now)"))
        assertTrue(gi.substringAfter("private fun ready(): Boolean {").substringBefore("\n    }\n").contains("if (live(now)) return true"))
        // a host that goes or comes never leaves the taps held
        assertTrue(fn("fun onDestroy() {").contains("GameInput.away = false"))
        assertTrue(host.substringAfter("fun onCreate(savedInstanceState: Bundle?) {").substringBefore("RuntimeArt.needed(this)").contains("GameInput.away = false"))
    }

    @Test fun aRebuiltHostRestoresTheSnapshot() {
        assertTrue(host.contains("fun hostRestore(): BottomSync.Snapshot? = null"))
        val create = host.substringAfter("fun onCreate(savedInstanceState: Bundle?) {").substringBefore("\n    fun onStart() {")
        assertTrue(create.contains("restored = shell.hostRestore()"))
        val gate = create.indexOf("GameInput.gate.restart()")
        val replay = create.indexOf("for (m in DocMemory.replay()) handleMessage(m)")
        assertTrue("the documents replayed after the gate's new start (non-current) and before the reader starts", gate in 0 until replay)
        assertTrue(create.contains("BottomSync.waitingGone(r, LauncherHooks.gameUp() || BottomSync.relaunchPending(SystemClock.elapsedRealtime()),"))
        // the room by the adopt route: decided at the first CURRENT interact document, no entry tap
        val interact = host.substringAfter("            \"interact\" -> {").substringBefore("\n            \"cappad\"")
        assertTrue(interact.contains("BottomSync.roomAfter(want, holderNow, HomeBack::roomsOf)"))
        assertTrue(interact.contains("show(Room.valueOf(it), adopt = true)"))
        val show = host.substringAfter("private fun show(r: Room, byB: Boolean = false, motion0: Boolean = true, adopt: Boolean = false) {")
            .substringBefore("\n    }\n")
        assertTrue("no entry tap on an adopt", show.contains("if (!adopt) touchRoomsEnter(was, r)"))
        assertTrue(show.contains("if (enterProfessions && !adopt)"))
        assertTrue("a pick meanwhile wins", show.contains("if (!adopt) restoreRoom = null"))
        assertTrue("the catch-up time", host.contains("bottom screen caught up"))
        // the app's own MainActivity never restores
        assertFalse(File(src, "MainActivity.kt").readText().contains("hostRestore"))
    }

    @Test fun theSilenceClockSkipsTheTimeAway() {
        val w = AddonSilence.Watch(0L)
        // a silent clock (no word yet in this game run): its start and its next say move on by the time away
        w.tick(10_000L, true, 5_000L, 0L)
        val from = w.from
        w.resumed(60_000L)
        assertEquals(from + 60_000L, w.from)
        // a clock that heard the add-ons stays as it is (a park is no silence, and no word is lost)
        val v = AddonSilence.Watch(0L)
        v.tick(10_000L, true, 5_000L, 0L); v.word(11_000L)
        val vf = v.from
        v.resumed(60_000L)
        assertEquals(vf, v.from)
        assertFalse(v.silent)
    }
}
