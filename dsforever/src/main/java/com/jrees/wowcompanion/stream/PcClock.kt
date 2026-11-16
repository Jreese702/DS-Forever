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
 * So BridgeLog moves each document's stamp by one shift, learnt here:
 *  - Desktop's welcome says its own clock on the same scale ("gt_now"): the shift is the difference, exact (a PC clock that merely
 *    drifted is mended too); within [SAME_MS] it is taken as none; nothing is learnt from the documents then;
 *  - an older Desktop says nothing: the LIVE documents (after "backlog_end") show the gap, and a whole number of quarter hours (every
 *    time zone is one) within [SNAP_SLACK_MS] of it is taken, but only from documents that KEEP TIME: the same gap (within [STEADY_MS])
 *    over at least [CONFIRM_MS] of the Thor's clock. A "live" document is not always a new one: Desktop 0.2.29 reads the game's log
 *    only after its bridge is up (and again when its WoW folder is chosen), so a Thor that connected in between gets the OLD log's
 *    documents after backlog_end, all at once; their gaps grow as fast as they are read, and a same-zone PC must never be shifted by
 *    their age. Until a shift is confirmed, and for a gap that is no time zone's (a PC clock a few minutes off), the stamps are left
 *    exactly as before. A new confirmed shift replaces the old one, so a wrong one heals by itself.
 * A shifted stamp stays on the scale (New Year: [wrap]). Pure: [wall] is the Thor's clock on the documents' scale (GameInput.wallYearMs
 * on the device, a fake in the tests); it is also the clock the [CONFIRM_MS] wait is measured on.
 */
class PcClock(private val wall: () -> Long) {
    /** ms to add to a PC stamp to put it on the Thor's clock */
    @Volatile var shiftMs = 0L
        private set
    /** Desktop said its clock (the welcome's gt_now): nothing is learnt from the documents */
    private var exact = false
    private var live = false
    /** the run of live documents keeping time: the first one's gap and when (the Thor's clock) it came; -1 = no run */
    private var runGap = 0L
    private var runAt = -1L

    /** a new connection's welcome; [pcNow] = Desktop's "gt_now" (-1 = an older Desktop) */
    fun welcome(pcNow: Long) {
        live = false
        runAt = -1L
        if (pcNow >= 0) {
            val d = year(wall() - pcNow)
            shiftMs = if (abs(d) <= SAME_MS) 0L else d
            exact = true
        } else {
            shiftMs = 0L
            exact = false
        }
    }

    /** the backlog is over: what follows came since the connection began */
    fun backlogEnd() { live = true }

    /** [gt] (a PC stamp, -1 = none) on the Thor's clock */
    fun map(gt: Long): Long {
        if (gt < 0) return gt
        if (!exact && live) learn(gt)
        return if (shiftMs == 0L) gt else wrap(gt + shiftMs)
    }

    private fun learn(gt: Long) {
        val now = wall()
        val d = year(now - gt)
        val q = zone(d)
        // no time zone's gap (an old document, or a PC clock minutes off): it teaches nothing and ends the run
        if (q == null) { runAt = -1L; return }
        if (runAt < 0 || abs(d - runGap) > STEADY_MS || zone(runGap) != q) { runGap = d; runAt = now; return }
        if (q != shiftMs && year(now - runAt) >= CONFIRM_MS) shiftMs = q
    }

    companion object {
        const val SAME_MS = 2_000L
        const val QUARTER_MS = 15 * 60_000L
        const val SNAP_SLACK_MS = 2 * 60_000L
        /** the live documents' gap may wander this much and still keep time (InputGate's own 20 s: the log is written in bursts) */
        const val STEADY_MS = 20_000L
        /** ... over at least this long of the Thor's clock before a shift is taken */
        const val CONFIRM_MS = 5_000L
        /** UTC-12 to UTC+14: no two clocks on Earth are further apart */
        const val MAX_ZONE_MS = 26 * 60 * 60_000L
        private const val DAY_MS = 24 * 60 * 60_000L
        private const val YEAR_MS = 365L * DAY_MS

        /** the day-of-year scale wraps at New Year: a difference is taken the short way round */
        fun year(d: Long): Long = when {
            d > YEAR_MS / 2 -> d - YEAR_MS
            d < -YEAR_MS / 2 -> d + YEAR_MS
            else -> d
        }

        /** a shifted stamp back onto the scale (InputGate.yearMs: 1 Jan 00:00 = one day, 31 Dec 23:59 = just under 366 days) */
        fun wrap(t: Long): Long = when {
            t >= YEAR_MS + DAY_MS -> t - YEAR_MS
            t < DAY_MS -> t + YEAR_MS
            else -> t
        }

        /** a raw gap -> the time-zone shift it shows (whole quarter hours, 0 included, near enough), else null */
        fun zone(d: Long): Long? {
            val q = (d.toDouble() / QUARTER_MS).roundToLong() * QUARTER_MS
            return if (abs(q) <= MAX_ZONE_MS && abs(d - q) <= SNAP_SLACK_MS) q else null
        }

        /** a raw gap -> the time-zone shift it shows, else 0 */
        fun snap(d: Long): Long = zone(d) ?: 0L
    }
}
