package com.jrees.wowcompanion

/**
 * OUR OWN BATTLE.NET SIGN-IN, the part without Android (LATER 29; the user, 2026-10-05: "I want to build my own, if we are able to
 * make the app detect the login screen somehow, it would be great to have a button for entering/revising Bnet Login, and a button
 * "Login" which triggers the script to log into the game"). "No typing into the game" is lifted FOR SIGN-IN ONLY.
 *
 * WHAT THE GAME TELLS US (General.log, measured on WoW Forever 2026-10-03 and 2026-10-05). Addons do not run on the login screen, so
 * the engine's own lines are all there is:
 *   [N][Lua] Switching to screen="AccountLogin" (from "none")          the login screen is up
 *   [N][Connection] Glue login started loginType=...                    a sign-in began (ours or the user's)
 *   [N][Connection] Battle.net logon queue update received position="2036" ...   waiting in Blizzard's queue
 *   [N][Connection] Battle.net logon form submission response received result="..." authenticatorRequired="true|false" ...
 *   [N][Connection] Fatal login error received result="Status has no name. (14400)"   it failed, with the game's reason
 *   [N][Lua] Switching to screen="..."                                   any other screen: the login screen is gone
 * "Battle.net logon response received result="ERROR_OK (0)"" is NOT success: it comes BEFORE the password form (10-03 11:30:55).
 *
 * THE TYPING (our own code; the WoW Forever author's macro is GPL and only showed that it can be done): a tap on the email field, the
 * old text deleted, the email typed; a tap on the password field, cleared, the password typed; Enter. Root `input -d 0` (display 0 is
 * the game; a bare `input` would type into the companion itself on display 4). The text travels on `su`'s stdin, never in a command
 * line of ours; `input` itself does get it as an argument for the moment it runs (the trade-off the user accepted in tracking).
 */
object HomeLogin {

    const val LOGIN_SCREEN = "AccountLogin"

    /** where the two fields are, as fractions of the game's screen (centre x; y from the top). MEASURED on WoW Forever 1.60.1 (70235),
     *  1920x1080, 2026-10-05 18:13: the email box's centre at (960, 559), the password box's at (960, 671) */
    var emailAt = 0.5f to 0.518f
    var passwordAt = 0.5f to 0.621f
    /** the game's own "Log In" button (centre (960, 825), same screenshot). The run ENDS with a click here, never Enter: the row now
     *  shows on every screen before the world (the user's pick), and at character select Enter would enter the world, while this spot
     *  holds nothing there */
    var logInAt = 0.5f to 0.764f

    /** the game whose login screen writes NOTHING to its logs (measured 2026-10-05 18:43: all of WoW Forever's logs 0 bytes from the
     *  client's start at 18:42:41 while its login screen was up), so its row shows from the client's start until the world */
    const val WOWFOREVER_PKG = "app.wowforever"
    /** the WoW Forever family: its own app and the private fork (DS Forever Launcher); both show the row the same way */
    val WF_PKGS = setOf(WOWFOREVER_PKG, GameLocator.DSLAUNCHER)

    /** past this, the log has flushed its 48 KiB buffer at least once: the client is past the login screen (a login screen and a
     *  sign-in wrote 9-11 KB, 2026-10-03 / 10-05) */
    const val SMALL_LOG = 64L * 1024

    /** the fallback: the row shows this long after the client appeared if the login screen's signal never came (measured 2026-10-05,
     *  3 launches: WoW Forever's own boot loader, then its login screen at 17-19 s) */
    const val LOGIN_SCREEN_FALLBACK_MS = 25_000L

    /**
     * BACK AT THE LOGIN SCREEN in the same client run (a logout and Back, a disconnect, a timeout), measured live 2026-10-06 08:53-09:03:
     * in the world the client held 2 realm connections and the addons wrote every few seconds; at character select 1 realm connection
     * and a quiet log; on the login screen NO game-server connection at all (not even Battle.net 1119: it opens only on Log In), only
     * HTTPS, and the log quiet. So: no connection to anything but HTTPS ([servers], loopback excluded) and the log unwritten for
     * [LOGIN_AGAIN_QUIET_S] (past GameInput's 25 s live window, so the start screen may come back). Any port counts, not just 3724
     * (b47 once saw the world with none on 3724).
     */
    const val LOGIN_AGAIN_QUIET_S = 30L
    fun loggedOut(servers: Long, logAgeS: Long) = servers == 0L && logAgeS >= LOGIN_AGAIN_QUIET_S

    /**
     * THE FIRST LINE OF EVERY RUN: the typing stops (exit [NOT_THE_GAME]) unless the top screen's front window IS a game (WoW Forever
     * or GameNative). A login screen read from a stale log must never send the email and password into the launcher or anything else
     * (2026-10-05 18:37: b45 showed the row with the game closed). Checked on the Thor: the launcher in front answers "not the game".
     */
    // THE REVIEW (2026-10-05) made it stricter: the top ACTIVITY alone passes with the notification shade, a dialog or an overlay over
    // the game, and one check before ten lines of typing leaves the rest unguarded. So it is a shell function `g`, run before EVERY
    // input line, that needs BOTH display 0's top resumed activity AND its FOCUSED WINDOW to be the game's own activity window (a
    // dialog's window carries the bare package with no "/activity", the shade and overlays another name; format measured on the Thor:
    // "  Display: mDisplayId=0 (organized)" then "  mCurrentFocus=Window{e8eeecd u0 <package>/<activity>}"). A failed check ends the
    // shell at once (exit 7): nothing after it is typed.
    const val GUARD = "g() { " +
        "dumpsys activity activities | sed -n '/Display #0 /,/Display #[1-9]/p' | grep -m1 'topResumedActivity=' | " +
        "grep -qE 'app[.](wowforever|gamenative)/' || exit 7; " +
        "dumpsys window displays | sed -n '/Display: mDisplayId=0 /,/Display: mDisplayId=[1-9]/p' | grep -m1 'mCurrentFocus=' | " +
        "grep -qE 'mCurrentFocus=Window[{][0-9a-f]+ u0 app[.](wowforever|gamenative)/' || exit 7; }"
    const val NOT_THE_GAME = 7

    /** a run the game never acknowledged ("Glue login started") this long after the typing ended: the typing did not reach it */
    // 45 s, not 15: the game wrote its sign-in lines only ~11 s after the press (17:47:34 -> flushed 17:47:45); too short a wait would
    // call a working sign-in "no answer" and open Login again while the game is already past its login screen
    const val NO_ANSWER_MS = 45_000L

    /** keys deleted before typing into a field: more than the longest text it can hold (Battle.net emails are at most 64 + 1 + 255) */
    const val CLEAR_KEYS = 80

    // ------------------------------------------------------------------------------------------------ the log

    sealed class Event {
        /** a screen of the game's login flow came up */
        data class Screen(val name: String) : Event()
        /** a sign-in began */
        object Started : Event()
        /** waiting in Blizzard's login queue, as of [at] (the line's "hh:mm"): the game writes NOTHING when the queue is left
         *  (measured 2026-10-05: back on the login screen, the log still ended on "position 2036"), so it is shown with its time */
        data class Queue(val position: Int, val at: String = "") : Event()
        /** the password form's answer: needs the authenticator code (typed by hand on the top screen) */
        object Authenticator : Event()
        /** it failed: the game's own reason, short */
        data class Failed(val reason: String) : Event()
        /** a companion addon line (WCB1): addons run only in the world, so the login is behind us */
        object World : Event()
    }

    private val SCREEN = Regex("""\[Lua] Switching to screen="([^"]+)"""")
    private val QUEUE = Regex("""logon queue update received position="(\d+)"""")
    private val FATAL = Regex("""Fatal login error received result="([^"]*)"""")
    private val FORM = Regex("""logon form submission response received result="([^"]*)".*?authenticatorRequired="(true|false)"""")

    /** one General.log line -> what it says about signing in, or null */
    fun parse(line: String): Event? {
        if ("WCB1|" in line) return Event.World
        if ("[Lua] Switching to screen=" in line) return SCREEN.find(line)?.let { Event.Screen(it.groupValues[1]) }
        if ("[Connection]" !in line) return null
        if ("Glue login started" in line) return Event.Started
        QUEUE.find(line)?.let { m -> return m.groupValues[1].toIntOrNull()?.let { Event.Queue(it, clock(line)) } }
        FATAL.find(line)?.let { return Event.Failed(reason(it.groupValues[1])) }
        FORM.find(line)?.let { if (it.groupValues[2] == "true") return Event.Authenticator }
        return null
    }

    /** "10/5 17:47:34.789  [N]..." -> "17:47", or "" */
    fun clock(line: String): String = Regex("""^\d+/\d+ (\d\d:\d\d)""").find(line)?.groupValues?.get(1) ?: ""

    /** the game's result text made short enough for one line: "Status has no name. (14400)" -> "Status has no name (14400)" */
    fun reason(raw: String): String {
        val r = raw.trim().replace(Regex("""\.\s*\("""), " (")
        return if (r.length <= 60) r else r.take(57) + "..."
    }

    // ------------------------------------------------------------------------------------------------ the state

    /** UNSURE = our typing ended but the game has written nothing yet (WoW Forever writes its sign-in late, or not at all on screen):
     *  it may well be signing in, so Login stays shut (the review: re-arming here invited a second run on the wrong screen) */
    enum class Phase { IDLE, RUNNING, QUEUE, AUTH, FAILED, UNSURE }

    /**
     * What the waiting screen shows. [showRow] = the sign-in row is offered; [phase] = where a sign-in is.
     * Fed every login-relevant line in order, and the game's client state by [client]; [note] / [client] say whether anything a
     * viewer draws changed.
     *
     * WHEN THE ROW SHOWS (the user's pick 2026-10-05, "Show it until you're in the world", after the measure that WoW Forever's login
     * screen writes nothing): for WoW Forever ([beforeWorld]), from the moment its client runs until the world (a companion addon
     * line, or a log past [SMALL_LOG]); the user presses it on the login screen. For any other game only when the log PROVES the
     * login screen ([atLogin]): GameNative signs in through its launcher and skips that screen.
     */
    class State {
        var screen: String? = null; private set
        var phase = Phase.IDLE; private set
        var queue = 0; private set
        var queueAt = ""; private set
        var reason = ""; private set
        /** the game wrote "Glue login started" since our last run began: the typing reached it, the rest is the game's */
        var started = false; private set
        /** the followed game's client (wine) runs */
        var clientUp = false; private set
        /** that game shows the row before the world (WoW Forever), not only on a proven login screen */
        var beforeWorld = false; private set
        /** this client run reached the world (an addon line, or a big log) */
        var inWorld = false; private set
        /** the client holds a connection to a WoW realm server (TCP 3724): it is SIGNED IN, whatever its (still empty) log says.
         *  Measured 2026-10-05: the login screen had only Battle.net (1119); at character select that was gone and 3724 was up, while
         *  the log stayed 0 bytes - so the log alone could not tell character select from the login screen */
        var realm = false; private set
        /** WoW Forever's login screen is (or has been, this client run) up: the client's first HTTPS connection opened, which in all
         *  3 measured launches happened exactly when the login screen appeared (t = 17 s; the boot loader before it holds only
         *  Battle.net), or [LOGIN_SCREEN_FALLBACK_MS] passed. Latched until the client run ends (the user: "appears a little early") */
        var screenUp = false; private set
        /** a sign-in began in this client run (the game's "Glue login started", or our own run) and has neither failed nor come back
         *  to the login screen since: the game is past it, or on its way */
        var signingIn = false; private set
        /** this client run got past its login screen (a realm connection, or the world): only then can it come BACK to it */
        var pastLogin = false; private set
        /** the followed log has not been written for [LOGIN_AGAIN_QUIET_S]: in the world the addons write every few seconds */
        var quiet = false; private set
        val atLogin get() = screen == LOGIN_SCREEN
        val showRow get() = clientUp && !inWorld && ((beforeWorld && screenUp) || atLogin)
        /**
         * OUT OF THE WORLD but still signed in (character select after a logout, a long loading screen): the run got past its login,
         * the row is not up, and the game has written nothing for [LOGIN_AGAIN_QUIET_S]. The user's pick 2026-10-06 ("show a waiting
         * screen there"): the bottom screen shows the start screen instead of the world it left; the next current document ends it.
         */
        val away get() = clientUp && pastLogin && quiet && !showRow

        /**
         * MAY LOGIN TYPE NOW (tracking's point, 2026-10-05: with the row shown before the world, a press at character select, in the
         * queue or at the authenticator box would type the email and password into whatever field is there). Only while nothing says
         * the game is past its login screen: no sign-in begun in this client run (all its logs stay EMPTY on the login screen, measured;
         * a sign-in flushed them ~11 s after the press, 17:47:34 -> 17:47:45), or the last one failed, or the log is back on the login
         * screen. Never during a run, a queue or the authenticator, and never on another screen the log names.
         */
        val canType get() = showRow && !realm && !signingIn && (screen == null || screen == LOGIN_SCREEN) &&
            phase != Phase.RUNNING && phase != Phase.QUEUE && phase != Phase.AUTH

        /** the watcher's view of the followed game: its client [up]; [preWorldRow] for WoW Forever; [logSize] of its log now */
        fun client(up: Boolean, preWorldRow: Boolean, logSize: Long, realmUp: Boolean = false, loginScreen: Boolean = true,
                   logQuiet: Boolean = false): Boolean {
            val before = listOf(clientUp, beforeWorld, inWorld, realm, phase, signingIn, screenUp, quiet)
            clientUp = up; beforeWorld = preWorldRow; quiet = logQuiet
            if (loginScreen) screenUp = true
            if (logSize > SMALL_LOG) inWorld = true
            realm = realmUp
            // a realm connection = signed in: our run (or the game's) is over and it worked; Login stays shut until it drops
            if (realmUp) { signingIn = true; if (phase == Phase.RUNNING || phase == Phase.UNSURE) phase = Phase.IDLE }
            if (realmUp || inWorld) pastLogin = true
            return before != listOf(clientUp, beforeWorld, inWorld, realm, phase, signingIn, screenUp, quiet)
        }

        fun note(e: Event): Boolean {
            val before = listOf(screen, phase, queue, queueAt, reason, inWorld, signingIn)
            when (e) {
                is Event.Screen -> {
                    // another screen: whatever ran is over (it signed in, or the game moved on); the login screen again keeps a failure
                    // on show (its reason stays until the next try), anything else starts clean. Back on the login screen = may type again
                    screen = e.name
                    if (e.name != LOGIN_SCREEN || phase != Phase.FAILED) phase = Phase.IDLE
                    if (e.name == LOGIN_SCREEN) signingIn = false
                }
                Event.Started -> { phase = Phase.RUNNING; reason = ""; started = true; signingIn = true }
                is Event.Queue -> { phase = Phase.QUEUE; queue = e.position; queueAt = e.at; signingIn = true }
                Event.Authenticator -> { phase = Phase.AUTH; signingIn = true }
                is Event.Failed -> { phase = Phase.FAILED; reason = e.reason; signingIn = false }
                Event.World -> { inWorld = true; pastLogin = true; phase = Phase.IDLE }
            }
            return before != listOf(screen, phase, queue, queueAt, reason, inWorld, signingIn)
        }

        /** a new log (the client restarted, or no game runs): nothing known until it says so */
        fun reset() {
            screen = null; phase = Phase.IDLE; queue = 0; queueAt = ""; reason = ""; started = false
            clientUp = false; beforeWorld = false; inWorld = false; signingIn = false; realm = false; screenUp = false
            pastLogin = false; quiet = false
        }

        /**
         * The same client run is back on its login screen ([loggedOut], seen twice in a row by the watcher): as at the run's start, but
         * the login screen is known to be up. False (nothing changes) unless the run had got past it.
         */
        fun backAtLogin(): Boolean {
            if (!pastLogin) return false
            screen = null; phase = Phase.IDLE; queue = 0; queueAt = ""; reason = ""; started = false
            inWorld = false; signingIn = false; realm = false; pastLogin = false; screenUp = true
            return true
        }

        /** our own script began (before the game's "Glue login started" line arrives): no second run until it is known how it ended */
        fun running() { phase = Phase.RUNNING; reason = ""; started = false; signingIn = true }

        /** our run failed or got no answer from the game at all (see [NO_ANSWER_MS]): shown like the game's own failures; Login may
         *  type again (the user's next press) */
        fun fail(why: String) { phase = Phase.FAILED; reason = why; signingIn = false }

        /** our run got no answer in [NO_ANSWER_MS]: unknown, so Login stays shut ([signingIn] kept) until the log says how it ended,
         *  the client restarts, or the user re-arms it on purpose through the gear ([rearm]) */
        fun noAnswer() { if (phase == Phase.RUNNING && !started) phase = Phase.UNSURE }

        /** the gear's "Save and log in": the user asked for a run themselves, whatever an earlier run left unknown */
        fun rearm() { if (!realm && (phase == Phase.UNSURE || phase == Phase.IDLE)) { signingIn = false; phase = Phase.IDLE } }
    }

    // ------------------------------------------------------------------------------------------------ the typing

    /** null when [s] can be typed (plain keyboard characters only), else why not */
    fun untypable(s: String): String? = when {
        s.isEmpty() -> "It is empty"
        s.any { it.code < 0x20 || it.code > 0x7E } -> "Only plain keyboard characters can be typed"
        else -> null
    }

    /**
     * The root shell lines that sign in: the guard; the email field, cleared, typed; the password field, cleared, typed; a click on
     * the game's Log In button ([logInAt], never Enter). [w] x [h] = the game's screen in the pixels `input -d 0` uses. Nothing here
     * is logged anywhere.
     */
    fun script(email: String, password: String, w: Int, h: Int): List<String> {
        val out = ArrayList<String>()
        out += GUARD
        fun send(cmd: String) { out += "g; input -d 0 $cmd" }        // the guard before every single line
        fun field(at: Pair<Float, Float>, text: String) {
            send("tap ${(at.first * w).toInt()} ${(at.second * h).toInt()}")
            send("keyevent MOVE_END")
            send("keyevent " + List(CLEAR_KEYS) { "DEL" }.joinToString(" "))
            for (piece in pieces(text)) send("text " + quote(piece))
        }
        field(emailAt, email)
        field(passwordAt, password)
        send("tap ${(logInAt.first * w).toInt()} ${(logInAt.second * h).toInt()}")
        return out
    }

    /**
     * [s] cut so that no piece holds "%s" (`input text` turns every "%s" into a space) and spaces travel as "%s": a '%' followed by
     * an 's' is split between two pieces.
     */
    fun pieces(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (i in s.indices) {
            val c = s[i]
            if (c == 's' && cur.endsWith("%")) { out += cur.toString(); cur.clear() }
            if (c == ' ') cur.append("%s") else cur.append(c)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** one shell word, single-quoted ('\'' for a quote inside) */
    fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
