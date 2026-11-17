package com.jrees.wowcompanion.stream

/**
 * THE PC'S STREAMING HOST DOESN'T ANSWER RTSP (the user's PC 2026-11-17: Windows refused Sunshine's RTSP port at boot, HTTP kept
 * answering, and the Thor retried "error 110" every 11 s for ever). The user's picks: "Full: PC + Thor", give-up = "Message + Retry
 * button". After [ASK_AFTER] RTSP timeouts in a row (HTTP fine: the stream got as far as RTSP) the Thor asks Desktop over the bridge
 * to check and restart its host ("host_check"), shows [RESTARTING] and tries again once Desktop says "ok"; a failure after that, a
 * "failed" answer, no answer in [ASK_WAIT_MS] or no bridge: [DOWN] with Retry, and no more tries by itself. Pure.
 */
class HostHeal {
    enum class Phase { IDLE, ASKED, RETRYING, GAVE_UP }
    enum class Next { NORMAL, ASK_PC, WAIT, RETRY, GIVE_UP }

    var phase = Phase.IDLE
        private set
    /** RTSP timeouts in a row */
    var timeouts = 0
        private set

    /** the stream ended at RTSP with a timeout; [hasBridge] = Desktop can be asked */
    fun rtspTimeout(hasBridge: Boolean): Next {
        timeouts++
        return when (phase) {
            Phase.IDLE -> when {
                timeouts < ASK_AFTER -> Next.NORMAL
                hasBridge -> { phase = Phase.ASKED; Next.ASK_PC }
                else -> { phase = Phase.GAVE_UP; Next.GIVE_UP }
            }
            Phase.ASKED -> Next.WAIT
            Phase.RETRYING, Phase.GAVE_UP -> { phase = Phase.GAVE_UP; Next.GIVE_UP }
        }
    }

    /** it ended some other way: the RTSP run is broken (the normal policy decides) */
    fun otherEnd() { if (phase == Phase.IDLE || phase == Phase.RETRYING) { phase = Phase.IDLE; timeouts = 0 } }

    /** Desktop's host_check_result: "ok" | "restarting" | "failed" | "streaming" (anything else: wait) */
    fun answer(state: String): Next {
        if (phase != Phase.ASKED) return Next.NORMAL
        return when (state) {
            "ok" -> { phase = Phase.RETRYING; Next.RETRY }
            "failed", "streaming" -> { phase = Phase.GAVE_UP; Next.GIVE_UP }
            else -> Next.WAIT
        }
    }

    /** the ask couldn't be sent, or no answer came in time */
    fun noAnswer(): Next = if (phase == Phase.ASKED) { phase = Phase.GAVE_UP; Next.GIVE_UP } else Next.NORMAL

    /** the stream started, or the player pressed Retry: start over */
    fun reset() { phase = Phase.IDLE; timeouts = 0 }

    companion object {
        const val ASK_AFTER = 2
        /** Desktop's heal: the first try at once, then 15 s and 45 s apart, each a few seconds to settle */
        const val ASK_WAIT_MS = 120_000L
        const val RESTARTING = "PC streaming host restarting"
        const val DOWN = "PC streaming host not responding"
        const val DOWN_DETAIL = "Restart DS Forever Desktop on the PC."
        private const val ETIMEDOUT = 110
        private const val ECONNREFUSED = 111

        /** Moonlight's start failed at the RTSP stage with a timeout (or a refusal: nothing listens on RTSP) */
        fun isRtspTimeout(error: Int, failedStage: String): Boolean =
            (error == ETIMEDOUT || error == ECONNREFUSED) && failedStage.contains("RTSP", ignoreCase = true)
    }
}
