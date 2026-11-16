package com.jrees.wowcompanion.stream

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * THE PC'S CLOCK MOVED ONTO THE THOR'S (issue #414, "Companion stuck on 'Waiting for the game' when PC and Thor are in different time
 * zones"): every add-on document carries the game's own clock (`_gt`: the General.log line's time, LOCAL time with no zone, on
 * LogTail's day-of-year scale), and the readers compare it with the Thor's clock (InputGate: within 20 s = written moments ago =
 * current; Interact, Dialogs, the padmap run check). In Streaming the log is the PC's: a PC on UTC+3 against a Thor on UTC+1 stamped
 * every document 2 h "in the future", nothing was ever current, and the bottom screen never left the start view.
 *
 * So BridgeLog moves each document's stamp by ONE shift per connection, learnt here:
 *  - Desktop's welcome says its own clock on the same scale ("gt_now"): the shift is the difference, exact (a PC clock that merely
 *    drifted is mended too); within [SAME_MS] it is taken as none;
 *  - an older Desktop says nothing: the first LIVE document (after "backlog_end": written since this connection began, so moments
 *    ago) shows the gap, and a whole number of quarter hours (every time zone is one) within [SNAP_SLACK_MS] of it is taken. Anything
 *    else (a PC clock a few minutes off) is left exactly as before. The backlog itself is never used to learn: it is old by design.
 * Pure: [wall] is the Thor's clock on the documents' scale (GameInput.wallYearMs on the device, a fake in the tests).
 */
class PcClock(private val wall: () -> Long) {
    /** ms to add to a PC stamp to put it on the Thor's clock */
    @Volatile var shiftMs = 0L
        private set
    private var learnt = false
    private var live = false

    /** a new connection's welcome; [pcNow] = Desktop's "gt_now" (-1 = an older Desktop) */
    fun welcome(pcNow: Long) {
        live = false
        if (pcNow >= 0) {
            val d = year(wall() - pcNow)
            shiftMs = if (abs(d) <= SAME_MS) 0L else d
            learnt = true
        } else {
            shiftMs = 0L
            learnt = false
        }
    }

    /** the backlog is over: what follows was written since the connection began */
    fun backlogEnd() { live = true }

    /** [gt] (a PC stamp, -1 = none) on the Thor's clock */
    fun map(gt: Long): Long {
        if (gt < 0) return gt
        if (!learnt && live) {
            learnt = true
            shiftMs = snap(year(wall() - gt))
        }
        return gt + shiftMs
    }

    companion object {
        const val SAME_MS = 2_000L
        const val QUARTER_MS = 15 * 60_000L
        const val SNAP_SLACK_MS = 2 * 60_000L
        /** UTC-12 to UTC+14: no two clocks on Earth are further apart */
        const val MAX_ZONE_MS = 26 * 60 * 60_000L
        private const val YEAR_MS = 365L * 24 * 60 * 60_000L

        /** the day-of-year scale wraps at New Year: a difference is taken the short way round */
        fun year(d: Long): Long = when {
            d > YEAR_MS / 2 -> d - YEAR_MS
            d < -YEAR_MS / 2 -> d + YEAR_MS
            else -> d
        }

        /** a raw gap -> the time-zone shift it shows (whole quarter hours, near enough), else 0 */
        fun snap(d: Long): Long {
            val q = (d.toDouble() / QUARTER_MS).roundToLong() * QUARTER_MS
            return if (q != 0L && abs(q) <= MAX_ZONE_MS && abs(d - q) <= SNAP_SLACK_MS) q else 0L
        }
    }
}
