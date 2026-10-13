package com.jrees.wowcompanion

import kotlin.math.abs

/**
 * IS THE GAME IN THE WORLD RIGHT NOW? Nothing is injected unless this says yes.
 *
 * An injected key or tap lands on whatever the top screen shows. At the login screen that is the
 * password field (2026-09-26: several minutes of commands went into it, with no error anywhere).
 * The app also renders hours-old data happily, so "the bottom screen looks alive" proves nothing.
 *
 * The proof is a document the game wrote MOMENTS AGO. LogTail stamps every document with the time
 * the game wrote it (`_gt`, from the log line), and the game's clock is the device's clock, so a
 * stamp within [stampMs] of now cannot be a replayed one. At start LogTail replays up to 8 MB of
 * backlog in one burst; those documents carry their old stamps and prove nothing.
 *
 * AND NOTHING WRITTEN BEFORE THE APP STARTED IS CURRENT (2026-09-30, tracking's decision under the user's
 * delegation). The stamp rule alone let the backlog's last ~20 s replay as current: at integ-home21's
 * third restart a profession window open 5 s before the restart re-entered the Professions room and
 * tapped the game (select + page, 15:08:46-47) with no touch. So the gate remembers the device clock of
 * the FIRST stamped document it sees (the app's start: LogTail replays at once) and counts a document as
 * current only when it was written after that, less [startSlackMs]. The game's stamps sit within ~0.1 s
 * of the device clock (measured on the Thor, 15:12). [restart] starts the rule again for a new LogTail.
 * Until the first document written after the start arrives, [live] stays false: GameInput and TapBridge
 * refuse to send, and every route that jumps only on current documents waits.
 *
 * Documents without a stamp fall back to the rule the Quests room uses: two documents at least
 * [spanMs] apart in arrival time, because a replayed backlog never spreads that far.
 *
 * Pure Kotlin on purpose (no android.*), so tools/hub_test can run the real file off the device.
 */
class InputGate(
    private val windowMs: Long = 25_000L,   // the heartbeat is 15 s; one missed is not yet "gone"
    private val spanMs: Long = 10_000L,
    private val stampMs: Long = 20_000L,
    private val startSlackMs: Long = 1_000L,   // the game's clock vs the device's, and the read lag (~0.1 s measured)
) {
    private var startWall = -1L    // the device clock (yearMs) at the first stamped document: the app's start
    private var firstAt = 0L
    private var lastAt = 0L
    private var lastCurrentAt = 0L

    /** the newest stamped document's distance from the device clock, for the status line */
    var skewMs = 0L
        private set

    /**
     * A document arrived at [now]. [gt] is its game-clock stamp (-1 = none) and [wall] is the
     * device clock in the same units (see [yearMs]). Returns true when the document is CURRENT:
     * written by the game moments ago, so what it says is the state of the game now.
     */
    fun note(now: Long, gt: Long = -1L, wall: Long = -1L): Boolean {
        if (lastAt == 0L || now - lastAt > windowMs) firstAt = now
        lastAt = now
        val current = if (gt >= 0 && wall >= 0) {
            skewMs = wall - gt
            if (startWall < 0) startWall = wall
            abs(wall - gt) <= stampMs && gt >= startWall - startSlackMs
        } else {
            now - firstAt >= spanMs
        }
        if (current) lastCurrentAt = now
        return current
    }

    /** a new LogTail is about to replay: documents written before its first one are backlog again */
    fun restart() {
        startWall = -1L
        lastCurrentAt = 0L
    }

    fun live(now: Long) = lastCurrentAt > 0 && now - lastCurrentAt < windowMs

    /** why [live] is false, in words for the status line; null while live */
    fun why(now: Long): String? = when {
        live(now) -> null
        lastAt == 0L -> "Waiting for the game"
        now - lastAt >= windowMs -> "The game has gone quiet"
        else -> "Checking the game is live"
    }

    companion object {
        private val DAYS_BEFORE = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)

        /**
         * The device clock in LogTail's `_gt` units: milliseconds since the start of the year,
         * local time, with the same month table (no leap day), so the two can be compared.
         * [month] is 1..12.
         */
        fun yearMs(month: Int, day: Int, hour: Int, minute: Int, second: Int, ms: Int): Long {
            val doy = DAYS_BEFORE[(month - 1).coerceIn(0, 11)] + day
            return ((doy * 24L + hour) * 60L + minute) * 60_000L + second * 1000L + ms
        }
    }
}
