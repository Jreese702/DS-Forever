package com.jrees.wowcompanion.stream

import com.jrees.wowcompanion.PausePower

/**
 * THE CLEAN DISCONNECT'S WAIT (feat/pause-power; the user, 2026-11-18: "When streaming this should also close wow on the PC and revert
 * everything back and be the clean way to disconnect"). The Thor sends "end_session" over the bridge; Desktop answers at once
 * "session_ending" (it heard), then "session_ended" once the PC's WoW has closed and the PC is put back (its controller mode, WoW's
 * window keys; the monitor's mode right after the stream has gone). Pure: StreamTop ticks it.
 *  - no connection to send it down: done at once (the stream stops; Desktop's own 5-minute close and its 30-second put-back stay the net);
 *  - no "session_ending" within [ackMs]: an older Desktop that ignores the word: done (the same net);
 *  - "session_ended": done with Desktop's word;
 *  - nothing more within [doneMs] (Desktop's own close waits 30 + 20 + 10 s at most, and the settle): done, said in the log.
 *
 * fix/1492-feedback (the user, 2026-11-18, test 1492: "closing from the power button didn't close the app"): the power button's end
 * ([closeOnAck] true) no longer waits for "session_ended". Desktop's close runs on its own (its Task, the bridge's disconnect never stops
 * it), and it waits out the game's own 20-second Exit Game countdown: 1492 sat on "Closing WoW on your PC" for ~20 s (Desktop: end_session
 * 15:10:04, "WoW closed (exit)" 15:10:24) and looked like it had not closed. Now "session_ending" (Desktop heard) ends the wait once the
 * Exit Game tap has gone down the stream ([ACK_CLOSE_MS]); the waiting screen's Exit game (closeOnAck false) still waits for Desktop's word.
 */
class SessionEnd(private val startMs: Long, val ackMs: Long = ACK_MS, val doneMs: Long = DONE_MS, val closeOnAck: Boolean = false) {
    /** null = not known yet (the send runs off the main thread) */
    private var sent: Boolean? = null
    private var acked = false
    private var ended: BridgeMsg.SessionEnd? = null

    fun sent(ok: Boolean) { sent = ok }
    fun said(m: BridgeMsg.SessionEnd) { if (m.done) ended = m else acked = true }

    /** the end's result once it is decided, null = wait on */
    fun check(now: Long): PausePower.Ended? {
        ended?.let { return PausePower.Ended(answered = true, wow = it.wow, putBack = it.putBack, why = it.why) }
        val dt = now - startMs
        return when {
            sent == false -> PausePower.Ended(answered = false, why = "no bridge connection")
            closeOnAck && acked && dt >= ACK_CLOSE_MS -> PausePower.Ended(answered = true, wow = "closing", why = "Desktop closes WoW and puts the PC back on its own")
            !acked && dt >= ackMs -> PausePower.Ended(answered = false, why = "no answer in ${ackMs / 1000} s (an older Desktop)")
            dt >= doneMs -> PausePower.Ended(answered = false, why = "Desktop still closing after ${doneMs / 1000} s")
            else -> null
        }
    }

    companion object {
        const val ACK_MS = 5_000L
        const val DONE_MS = 90_000L
        /** a game Exit Game tapped over the stream goes down it before the picture stops */
        const val QUIT_TAP_SETTLE_MS = 600L
        /** [closeOnAck]: the earliest end after Desktop's "session_ending" (the Exit Game tap down the stream, the picture stopped) */
        const val ACK_CLOSE_MS = 1_000L
    }
}
