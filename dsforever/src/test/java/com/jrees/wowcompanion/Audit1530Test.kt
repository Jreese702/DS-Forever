package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE 1530 AUDIT (test build 1530 = int/1529 with test 1529 merged in): perf-1529's slower add-on flushes in fights and log polls vs the
 * predicted cast bar's wait, the log's backlog read at the old pace, and hold to cast's badges while Party mode is on (Party mode has no
 * holds: PadMap's OV.hold.mapNow says "party").
 */
class Audit1530Test {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private fun kt(n: String) = File(src, n).readText()
    private val sink = File(src, "../../../../assets/companion/addons/WowCompanion/Sink.lua").readText()

    /** Sink's fight interval in ms (sink2's FLUSH_COMBAT) */
    private fun fightFlushMs(): Long {
        val m = Regex("""local FLUSH_COMBAT = ([0-9.]+)""").find(sink) ?: error("no FLUSH_COMBAT in Sink.lua")
        return (m.groupValues[1].toDouble() * 1000).toLong()
    }

    /**
     * A cast-time press in a walking fight: the cast start is a throttled push, and the walking ticker (0.1 s) lands the next flush 0.3 or
     * 0.4 s after the last (tools/test_combat_light.py); then up to one log poll, two game frames at 40 fps (the press reaching the game,
     * the end-of-frame push) and one paced bottom-screen frame. The predicted bar must outlive that with room to spare, or it drops and
     * the real bar comes in part-way (the old "starts at 25%")
     */
    @Test fun thePredictedCastBarWaitsLongerThanAFightsSlowestStart() {
        val worst = fightFlushMs() + 100L + LogTail.POLL_MS + 2 * 25L + 33L
        assertTrue("the predicted bar's wait (${CastPredict.CONFIRM_MS} ms) covers a fight's slowest real start ($worst ms) + 100 ms",
            CastPredict.CONFIRM_MS >= worst + 100L)
        assertTrue("and still lets a press that never cast go within a second", CastPredict.CONFIRM_MS <= 1000L)
    }

    /** a backlog (the last read filled MAX_READ: a fresh tail on a long log) is read at 1528's 20 ms pace; the steady state stays 50 ms */
    @Test fun aBacklogIsReadAtTheOldPace() {
        assertEquals(LogTail.POLL_MS, LogTail.delayAfter(filled = false))
        assertEquals(20L, LogTail.delayAfter(filled = true))
        val t = kt("LogTail.kt")
        assertTrue(t.contains("if (running) handler.postDelayed(this, delayAfter(filled))"))
        assertTrue("the plain read says when it filled", t.contains("filled = n.toLong() >= MAX_READ"))
        assertTrue("each poll starts unfilled (a quiet poll goes back to 50 ms)",
            t.substringAfter("private fun poll() {").trimStart().startsWith("filled = false"))
    }

    /** Party mode on: no hold badges on Home's buttons (the add-on never fires a hold then), and a Party mode change re-applies them */
    @Test fun noHoldBadgesWhilePartyModeIsOn() {
        val home = kt("HomeView.kt")
        assertTrue(home.contains("castView.holdRefs = PadMapModel.holdRefs(map, combat = fightNow && map.faceCc != false)" +
            ".takeIf { map.holdOn != false && !partyOnHere() }"))
        val changed = home.substringAfter("fun partyChanged() {").substringBefore("\n    }\n")
        assertTrue("partyChanged re-applies the badges", changed.contains("holdApply()"))
    }
}
