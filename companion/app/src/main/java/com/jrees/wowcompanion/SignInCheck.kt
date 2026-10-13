package com.jrees.wowcompanion

import com.jrees.wowcompanion.SetupFlow.SignIn

/**
 * THE SIGN-IN ON THE BOTTOM SCREEN (Track C M3). Pure; CompanionHost ticks it once a second, feeds it the game's traffic and hands the
 * result to [SetupBridge.signIn]. The user's rulings: buttons ONLY once a failure is confirmed (test10: "I only really want to see those
 * buttons if the login fails"), then they stay until past the login screen; "Keyboard" toggles the top screen's keyboard.
 *
 * MEASURED, per second from the client's read of login.txt (rx/tx, a second without a DSNET line = 0/0):
 * - refusals (test3 11:28, test8 13:56): the auth exchange, then the connection goes DEAD - exactly 0/0 for 36 s (t3) and 19 s (t8,
 *   one 52 B blip, then 0/0 again);
 * - successes (10-09 h1/h2/h3, test10 16:48): the exchange goes on into two-way keepalives (rx AND tx) every 1-3 s; their longest
 *   0/0 run is 5 s (h1, h3 at character select). test10 had NO big burst (character select cached: 47 KB/s at most), so traffic
 *   VOLUME is no sign of success, and its false "FAILED, 30 s" came from the old backstop.
 * So:
 * - FAILED = after the auth (a second of >= [AUTH_BYTES]) [ZERO_TICKS] seconds in a row of exactly 0/0 (t3 ~15 s, t8 ~13 s after the
 *   read; margin 3 over the successes); with no exchange at all, [NO_EXCHANGE_TICKS] seconds of 0/0. STICKY until past the login screen
 *   (the buttons do not flap with a stray keepalive).
 * - SIGNED IN ([signedIn]) = >= [ALIVE_AFTER] seconds since the read, never failed, and >= [ALIVE_TICKS] two-way seconds in the last
 *   [ALIVE_WINDOW]. CompanionHost turns it into NativeLogin's pastLogin; the companion's in-world documents are the hard backstop.
 * - SIGNING (no buttons), RESTARTING / RESTART (after "Update login"), TYPING ("Keyboard" pressed after a failure, or no saved login).
 * - AUTH: the game's log asked for the authenticator code ([authenticator]). The game waits with its connection alive (keepalives,
 *   or quiet), so neither SIGNED IN nor FAILED is read from the traffic until the log moves on (a player's report 2026-10-13: the
 *   keepalives read as signed in, the buttons went and the keyboard closed).
 */
class SignInCheck {
    private var restartAt = 0L
    private var typing = false
    private var fatalAt = 0L
    private var trafficFor = 0L
    private var authSeen = false
    private var zeros = 0
    private var ticks = 0
    private val recent = ArrayDeque<Boolean>()
    /** the read this check found refused (sticky until past the login screen) */
    private var failedRead = -1L
    var state = SignIn.NONE
        private set
    /** "Restart the game to sign in" applied in this sign-in: its button stays with the others until past the login screen */
    var restartOffered = false
        private set
    /** the traffic says this sign-in got through (two-way keepalives after the exchange) */
    var signedIn = false
        private set
    /** the game is waiting for the authenticator code (its log said so, this run) */
    var authenticator = false
        private set

    /** the log's authenticator state; leaving it starts the traffic reading afresh (the wait's quiet seconds are not a refusal) */
    fun authenticator(on: Boolean) {
        if (on == authenticator) return
        authenticator = on
        zeros = 0; recent.clear(); signedIn = false
    }

    fun fatal(now: Long) { fatalAt = now }

    /** "Update login" was submitted: the game restarts now (a new run resets this check) */
    fun restarting(now: Long) { restartAt = now; typing = false }

    /** "Keyboard" pressed: the headline says to type on the game screen; the buttons stay */
    fun type() { typing = true }

    /** a new client run (the game screen came up): nothing carried over */
    fun reset() {
        restartAt = 0L; typing = false; fatalAt = 0L; failedRead = -1L
        trafficFor = 0L; authSeen = false; zeros = 0; ticks = 0; recent.clear(); signedIn = false
        restartOffered = false; authenticator = false; state = SignIn.NONE
    }

    /** one second of the game's traffic (the launcher's loop), counted from this run's read of login.txt on */
    fun traffic(firstReadAt: Long, rx: Long, tx: Long) {
        if (firstReadAt <= 0L) return
        if (firstReadAt != trafficFor) {
            trafficFor = firstReadAt; authSeen = false; zeros = 0; ticks = 0; recent.clear(); signedIn = false
        }
        ticks++
        if (rx >= AUTH_BYTES) authSeen = true
        zeros = if (rx == 0L && tx == 0L) zeros + 1 else 0
        recent.addLast(rx > 0L && tx > 0L)
        while (recent.size > ALIVE_WINDOW) recent.removeFirst()
        if (!authenticator && failedRead != firstReadAt && ticks >= ALIVE_AFTER && zeros == 0 && recent.count { it } >= ALIVE_TICKS) signedIn = true
    }

    /**
     * [noFile] = this run has no login.txt at all (no saved login): the game's own login screen; the buttons offer saving one and the
     * keyboard (there is nothing to fail, and the user has to type).
     */
    fun tick(now: Long, firstReadAt: Long, pastLogin: Boolean, noFile: Boolean = false): SignIn {
        if (pastLogin || (signedIn && trafficFor == firstReadAt)) {
            restartAt = 0L; typing = false; restartOffered = false
            state = SignIn.NONE
            return state
        }
        if (!authenticator && firstReadAt > 0L && failedRead != firstReadAt && refused(firstReadAt)) failedRead = firstReadAt
        val failed = firstReadAt > 0L && failedRead == firstReadAt
        state = when {
            restartAt > 0L && now - restartAt < RESTART_FAIL_MS -> SignIn.RESTARTING
            restartAt > 0L -> { restartAt = 0L; restartOffered = true; SignIn.RESTART }
            restartOffered && !typing -> SignIn.RESTART
            authenticator -> SignIn.AUTH
            failed -> if (typing) SignIn.TYPING else SignIn.FAILED
            firstReadAt > 0L -> SignIn.SIGNING
            noFile -> SignIn.TYPING
            else -> SignIn.NONE
        }
        return state
    }

    private fun refused(readAt: Long) =
        fatalAt >= readAt || (trafficFor == readAt && (if (authSeen) zeros >= ZERO_TICKS else zeros >= NO_EXCHANGE_TICKS))

    companion object {
        /** the restart closes the game within a few seconds; still the same run after this long = it did not happen */
        const val RESTART_FAIL_MS = 15_000L
        /** a second of the auth exchange (measured 1.2-19 KB) */
        const val AUTH_BYTES = 1_000L
        /** seconds in a row of exactly 0/0 after the auth = refused (successes: at most 5) */
        const val ZERO_TICKS = 8
        /** no exchange at all after the read: this long dead = refused */
        const val NO_EXCHANGE_TICKS = 30
        /** signed in: this many seconds after the read at least ... */
        const val ALIVE_AFTER = 15
        /** ... this many two-way seconds in the last [ALIVE_WINDOW] */
        const val ALIVE_TICKS = 4
        const val ALIVE_WINDOW = 10
    }
}
