package com.jrees.wowcompanion

/**
 * THE CAPTCHA HOLD (the user, 2026-11-13: "yes" to keeping the saved sign-in file longer behind a captcha, an approved exception to
 * the 20 s rule of the private login.txt; #271 "i cannot get past the captcha", #247 "no captchA login", #235 "dc on authenticator
 * code into captcha dc"). The client reads login.txt as its login screen appears and AGAIN when it sends the sign-in form. Behind a
 * captcha that send waits for the player (the trail of 10-06: form with a captcha 21:09:10, sent 21:09:40), so it came after the
 * file had gone 20 s after the first read, and the sign-in had no credentials.
 *
 * So when the 20 s after the client's FIRST read come while there is a [reason] (the bottom screen's sign-in Keyboard is open, or the
 * game's log said its form asks for a captcha), the file stays until the client's NEXT read + 20 s; the reason is asked again at that
 * deadline (a wrong captcha asks for another), and the file never stays past [CAP_MS] after the first read. With no reason at the
 * deadline it goes, exactly as before. The realm connection, the world and the game's close still delete it at once: NativeLogin
 * deletes the file there and calls [stop].
 *
 * Presence and timing only: nothing here opens, reads or names the file or anything in it; [delete] is NativeLogin's. Main thread.
 * Pure (the clock and the timer are given), so the off-device tests drive every path.
 */
internal class LoginHold(
    /** a monotonic clock, ms (NativeLogin: SystemClock.elapsedRealtime) */
    private val now: () -> Long,
    /** run [Runnable] after [Long] ms (NativeLogin: its main Handler) */
    private val post: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    /** why the file is kept right now ("the Keyboard is open", "the game asks for a captcha"), or null = no reason */
    private val reason: () -> String?,
    /** deletes the file now; [why] is the sign-in log's words for it */
    private val delete: (why: String) -> Unit,
    /** the sign-in log (step names only) */
    private val log: (String) -> Unit = {},
) {
    /** the client has read this file (and nothing deleted it since): only then is anything timed */
    var reading = false; private set
    /** when the client first read this file (meaningful while [reading]) */
    var firstReadAt = 0L; private set
    /** past its 20 s with a reason: kept for the client's next read */
    var holding = false; private set
    /** holding, and no read since the last deadline: the next read sets the deadline */
    var waitingRead = false; private set

    private val due = Runnable { deadline() }
    private val capped = Runnable {
        if (!reading) return@Runnable
        stop()
        delete("${CAP_MS / 1000} s after the client's first read (the hold's cap)")
    }
    /** when [due] should run on [now]'s clock (0 = not pending), for [tick] */
    private var dueAt = 0L

    private fun schedule(ms: Long) {
        cancel(due)
        dueAt = now() + ms
        post(due, ms)
    }

    /** the client's first read of this file: it goes [AFTER_READ_MS] from now, unless a reason holds it then */
    fun firstRead() {
        stop()
        reading = true
        firstReadAt = now()
        schedule(AFTER_READ_MS)
    }

    /** a later read by the client. Only a held file waiting for it moves: then it goes 20 s after this read (never past the cap) */
    fun read() {
        if (!reading || !waitingRead) return
        waitingRead = false
        val t = now()
        val at = minOf(t + AFTER_READ_MS, firstReadAt + CAP_MS)
        schedule(maxOf(0L, at - t))
        log("login.txt read again by the client ${(t - firstReadAt) / 1000} s after its first read (held): deleting ${(at - t) / 1000} s after this read")
    }

    /**
     * THE TIMES ON THE WALL CLOCK (the review of 2026-11-13): the Handler's delays count uptime, which stands still while the device
     * sleeps, so a Thor put to sleep at the captcha kept the file past the user's 3 minutes by the length of the sleep. NativeLogin
     * calls this with each second's traffic sample (main thread): a deadline or the cap that [now] (elapsedRealtime, sleep included)
     * says has passed runs at once.
     */
    fun tick() {
        if (!reading) return
        val t = now()
        when {
            t >= firstReadAt + CAP_MS -> if (holding) capped.run() else { cancel(due); deadline() }
            dueAt > 0L && t >= dueAt -> { cancel(due); deadline() }
        }
    }

    /**
     * the client has read the file and the hold has it, or would keep it at its deadline (a reason now): the unread file's cap
     * (NativeLogin.capCheck) leaves it to the hold, so a slow login screen whose read came near that cap keeps its captcha hold
     */
    fun keeps(): Boolean = reading && (holding || reason() != null)

    private fun deadline() {
        dueAt = 0L
        if (!reading) return
        val t = now()
        val why = reason()
        val left = firstReadAt + CAP_MS - t
        if (why != null && left > SLACK_MS) {
            if (!holding) {
                holding = true
                post(capped, left)
                log("login.txt kept past its ${AFTER_READ_MS / 1000} s ($why): until the client's next read + ${AFTER_READ_MS / 1000} s, " +
                    "at most ${CAP_MS / 1000} s after its first read")
            } else log("login.txt still held ($why): waiting for the client's next read")
            waitingRead = true
            return
        }
        val held = holding
        stop()
        delete(if (held) "${AFTER_READ_MS / 1000} s after the client's read in the hold" else "${AFTER_READ_MS / 1000} s after the client's first read")
    }

    /** the realm connection is up ([src]: where it was seen): the sign-in is done, a read file goes at once (held or not) */
    fun realmUp(src: String) = signedIn("the realm connection is up ($src)")

    /** the run is in the world (the add-on's documents are current): a read file goes at once (held or not) */
    fun world() = signedIn("in the world")

    private fun signedIn(why: String) {
        if (!reading) return
        stop()
        delete(why)
    }

    /** the file went another way (NativeLogin's clear: the game's close, a new run, the root probe's realm connection...): nothing more is timed */
    fun stop() {
        cancel(due); cancel(capped)
        reading = false; firstReadAt = 0L; holding = false; waitingRead = false; dueAt = 0L
    }

    companion object {
        /** the file's life after the client's first read with no reason to hold it (NativeLogin's device tests of 2026-10-08) */
        const val AFTER_READ_MS = 20_000L
        /** the user's cap: never kept past 3 minutes after the first read, whatever holds it */
        const val CAP_MS = 180_000L
        /** a deadline this close to the cap is the cap's */
        private const val SLACK_MS = 1_000L
    }
}
