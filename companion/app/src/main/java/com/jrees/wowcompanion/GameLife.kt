package com.jrees.wowcompanion

import java.io.File

/**
 * THE GAME'S LIFE IN DS FOREVER'S OWN LOG (issue #12, 1.0.9: "dont load": the game had reached the world, ended around 16:44 and
 * the report could not say how; logcat had been flooded and nothing of it was left). Into DiagLog's rolling FILE, which logcat's
 * eviction cannot touch: "game: started", "game: in the world after N s", "game: ended with status N / signal S after M min
 * (MemAvailable X MiB; indoor maps: …)", "launcher: back". An abnormal end leaves the launcher's touch-only note
 * ("The game closed (…). Send a problem report?") until it is dismissed, a report is opened or the game starts again.
 */
object GameLife {
    @Volatile private var startedAt = 0L
    @Volatile private var worldSeen = false
    @Volatile private var ended = false
    /** when the guest program last ended (epoch ms), 0 = not in this process. Issue #139 (2026-11-11): the launcher's game screen
     *  (LauncherHooks.gameUp) stays "up" until its view detaches, after the program has ended; DependencyAddons.gameUpNow reads both */
    @Volatile var endedAt = 0L
        private set
    /** the launcher's note after an abnormal end; null = none */
    @Volatile var note: String? = null
        private set

    /**
     * The guest program's exit status in words: 0 = closed normally; a signal (128 + n, as a shell reports it, or a negative n)
     * names it, with what Android's memory killer looks like (SIGKILL); anything else is the program's own error status.
     */
    fun describe(status: Int, closer: String? = null): String {
        val sig = when { status > 128 && status < 160 -> status - 128; status < 0 -> -status; else -> 0 }
        return when {
            status == 0 -> "closed normally"
            closer != null -> "was closed by DS Forever ($closer${if (sig > 0) ", signal $sig" else ", status $status"}), not by Android"
            sig == 9 -> "was killed (signal 9: Android may have needed the memory)"
            sig == 11 -> "crashed (signal 11)"
            sig == 6 -> "aborted (signal 6)"
            sig > 0 -> "was stopped by signal $sig"
            else -> "ended with error status $status"
        }
    }

    /**
     * WHO CLOSED IT (issue triage 2026-11-11: our own "Restart the game" from Settings ends the old game with signal 9, and the report
     * then said "signal 9: Android may have needed the memory"): set by the launcher's restart / Exit game just before it closes the
     * game, read by [ended], cleared by [started]. null = not us (Android, a crash or the game itself).
     */
    @Volatile var closer: String? = null

    /** an end the launcher should offer a report for (not one DS Forever made itself: [closer]) */
    fun abnormal(status: Int, closer: String? = null): Boolean = status != 0 && closer == null

    fun minutes(ms: Long): String = if (ms < 60_000) "${ms / 1000} s" else "${ms / 60_000} min"

    /** the note's words */
    fun noteText(status: Int, ranMs: Long): String =
        "The game ${describe(status)} after ${minutes(ranMs)}. If that wasn't you, a problem report helps us find out why."

    // ------------------------------------------------------------------ the events (the launcher and the bottom screen call these)

    fun started(now: Long = System.currentTimeMillis()) {
        startedAt = now; worldSeen = false; ended = false; note = null; closer = null; loads = 0; loadingAt = 0L; worldAt = 0L; loadEndAt = 0L
        lastLoadAt = 0L
        leftAt = 0L
        runCatching { LoadHold.windowStarted() }                   // Light 8: a new game's window (nothing to clear on another Thor)
        DiagLog.event("game", "started")
        LastRun.gameStarted(now)                                   // 1.0.17: kept across the app's own death
        // issue triage 2026-11-14 (the 1.0.29 - 1.0.31 low-memory cluster; the user: "yes on the triage things"): the game is about to
        // take gigabytes; DS Forever lets go of what is not on show and collects first (nothing when the watch is not running)
        MemWatch.relieve("game start")
    }

    /** 1.0.19: the game has reached the world and not ended (the LB picture only then: the user, "The LB menu is also appearing in the app
     *  startup screen before the game starts") */
    val worldNow: Boolean get() = worldSeen && !ended

    /** when this game started (epoch ms), 0 = none in this process (PlayGate.besideNow: Android's warnings since then) */
    val startedAtMs: Long get() = startedAt
    /** a loading screen in the world is up now (its end not seen yet) */
    val loadingNow: Boolean get() = worldNow && loadingAt != 0L

    /**
     * How long the game has been in the world with no loading screen (the user's pick, 2026-11-16: the world map beside the game on
     * the Thor Max, PlayGate.besideNow): since the world entry, the last loading screen's end or the world's return after a logout
     * ([worldBack]); 0 = not in the world, one is up, or the world closed ([worldLeft]) and is not back yet
     */
    fun calmMs(now: Long = System.currentTimeMillis()): Long =
        if (!worldNow || loadingAt != 0L || leftAt != 0L) 0L else (now - maxOf(worldAt, loadEndAt)).coerceAtLeast(0L)
    @Volatile private var worldAt = 0L
    @Volatile private var loadEndAt = 0L

    /**
     * THE WORLD CLOSED AND CAME BACK (review of the world map beside the game, 2026-11-16; the user's pick: "keep the memory relief and
     * the pause points"): Hub's logout word ("bye", PLAYER_LOGOUT: a logout to the character list or a /reload) closes the world. The
     * add-ons are silent until the next world is up, so neither the character list nor that world's loading screen sends anything, and
     * the calm since the last load kept counting through both. Now [calmMs] is 0 from the word until the add-on's next hello (the
     * world back), and the calm counts from that hello: the next world's load is a pause point like any loading screen.
     */
    fun worldLeft(now: Long = System.currentTimeMillis()) { if (worldNow) leftAt = now }
    /** the add-on's hello after a logout word ([worldLeft]): the world is back, its calm starts now */
    fun worldBack(now: Long = System.currentTimeMillis()) { if (leftAt != 0L) { leftAt = 0L; worldAt = now; runCatching { LoadHold.windowStarted() } } }
    /** the logout word came and the add-on's hello not yet */
    val awayNow: Boolean get() = worldNow && leftAt != 0L
    @Volatile private var leftAt = 0L

    /** the first CURRENT document from the add-on: the game is in the world */
    fun inWorld(now: Long = System.currentTimeMillis()) {
        if (worldSeen || startedAt == 0L) return
        worldAt = now
        worldSeen = true
        runCatching { LoadHold.windowStarted() }                   // Light 8: the world entry's own window
        DiagLog.event("game", "in the world after ${minutes(now - startedAt)}")
        LastRun.gameInWorld()
        // issue triage 2026-11-14: the reports' kills came 7 - 36 s after this, the game still loading the world: the same relief.
        // Verification of the fix (the user, 2026-11-13: "yes on the triage things"): the start screen that draws the loading pictures
        // closes on this same document, so a relief at once found them drawn under Scene's 2 s and kept them until its own 15 s idle
        // (no collection then: #250 and #254 were killed 8 s and 7 s after this). [WORLD_RELIEF_MS] later they go, and the one collection
        // frees them
        MemWatch.relieve("world", WORLD_RELIEF_MS)
        runCatching { Scene.loadingOver("the world is up") }       // test build 1489 (#56): the loading pictures go at once
    }
    /** the world relief's wait: past Scene's [Scene.TRIM_IDLE_MS] after the start screen closes, well before the reports' first kills */
    const val WORLD_RELIEF_MS = Scene.TRIM_IDLE_MS + 500L

    /**
     * RELIEF AT ZONE CHANGES (memory relief 2; the user's picks, 2026-11-14: "RELIEF AT ZONE CHANGES: the add-on reports loading
     * screens (LOADING_SCREEN_ENABLED / DISABLED, plain events, a tiny doc ...; no polling) and the app runs MemWatch.relieve at each
     * loading screen in long sessions (same sources as world entry), logged to mem.log"). The triage's reports: the game's memory jumped
     * 1 - 2 GB within seconds of Play, the world OR A ZONE; Play and the world had their relief, a zone in a long session none.
     * Core.lua (core1) sends {"type":"load","on":true} the moment a loading screen comes up (flushed at once: its timers may not run
     * during the load) and {"on":false} when it goes. Each one that comes up in the world gets the same [MemWatch.relieve] as the world
     * entry (every trim registration at running-low, one collection when due), "relief (loading screen N)" in mem.log; its end is noted
     * there with how long it took. Before the world (the login's own loading screen) the world relief covers it. Returns what it did,
     * for the tests: the relief's name, the end's note, or null.
     */
    fun loadingScreen(on: Boolean, now: Long = System.currentTimeMillis()): String? {
        if (!worldNow) return null
        if (on) {
            loadingAt = now; lastLoadAt = now; loads++
            runCatching { LoadHold.windowStarted() }               // Light 8: this loading screen's window
            val why = "loading screen $loads"
            MemWatch.relieve(why)
            return why
        }
        val at = loadingAt
        if (at == 0L) return null                                  // its start was never seen (the login's, a lost line)
        loadingAt = 0L; loadEndAt = now
        val l = "loading screen $loads over after ${minutes((now - at).coerceAtLeast(0L))}"
        MemWatch.note(l)
        runCatching { Scene.loadingOver("loading screen $loads over") }
        return l
    }
    /**
     * LIGHT 8'S LOAD-TIME WINDOW NOW (LoadHold.deferNow; LIGHT8_PLAN.md change 4): the login's (from [started] - pressing Play - until
     * [inWorld]; [loadingScreen] fires only once in the world, so the login's own load keys off the game's start), the world entry's
     * (until 30 s after [inWorld] or [worldBack]) and the last loading screen's in the world (until 5 s after its end). Nothing once
     * the game has ended - a loading screen up when it died included (its "over" never comes then). Read by LoadHold only
     */
    fun holdWindow(now: Long = System.currentTimeMillis()): Boolean =
        LoadHold.deferNow(if (ended) 0L else startedAt, if (worldSeen) worldAt else 0L, if (ended) 0L else lastLoadAt, loadingAt != 0L,
            loadEndAt, now)
    /** when the last loading screen in the world came (kept after its end; 0 = none in this game) */
    @Volatile private var lastLoadAt = 0L

    /** the loading screens seen in the world in this game run, and when the one up now came (0 = none up) */
    @Volatile var loads = 0
        private set
    @Volatile private var loadingAt = 0L

    /** the guest program ended with [status] (XServerScreen's termination callback) */
    fun ended(status: Int, now: Long = System.currentTimeMillis()) {
        if (ended) return
        ended = true
        endedAt = now
        LastRun.gameEnded()
        val ran = if (startedAt == 0L) 0L else now - startedAt
        val mem = memAvailableMiB()?.let { "$it MiB" } ?: "unknown"
        val by = closer
        val indoor = runCatching { IndoorSchedulerStep.status?.let { "${it.phase.name.lowercase()} ${it.ready}/${it.total}" } }.getOrNull() ?: "not running"
        DiagLog.event("game", "ended: ${describe(status, by)} (status $status) after ${minutes(ran)}${if (worldSeen) "" else ", before reaching the world"}; " +
            "MemAvailable $mem; indoor maps: $indoor")
        // #577: closed as it started (proot / box64 / Wine gave up before the client ran): its own output into the log and the report
        val quick = quickExit(status, ran, by, startedAt != 0L)
        quickExits = if (quick) quickExits + 1 else 0
        if (quick) runCatching {
            val (lines, before) = GuestOutput.tail()
            DiagLog.event("game", "closed as it started ($ran ms, status $status, $quickExits in a row); its output: " +
                if (lines.isEmpty()) "none" else "${lines.size} lines${if (before > 0) " (and $before before them)" else ""}")
            for (l in lines) DiagLog.event("game output", l)
            val at = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(now))
            GuestOutput.lastQuickExit = "status $status after $ran ms ($quickExits in a row, $at):\n" + GuestOutput.block(lines, before)
        }
        note = if (abnormal(status, by)) (if (quick) quickNoteText(status) else noteText(status, ran)) else null
    }

    /** #577: a run that closed within this long, with an error and not by us, closed as it started (the client never ran) */
    const val QUICK_EXIT_MS = 2_000L
    /** the runs in a row that closed as they started (0 after any other end) */
    @Volatile var quickExits = 0
        private set
    fun quickExit(status: Int, ranMs: Long, closer: String?, started: Boolean): Boolean =
        started && status != 0 && closer == null && ranMs in 0 until QUICK_EXIT_MS

    /** the note's words when the game closed as it started (#577) */
    fun quickNoteText(status: Int): String =
        "The game closed as it started (${describe(status)}). A problem report holds what it said: please send one."

    /** the launcher's screen is back (the game screen gone) */
    fun launcherBack() { if (startedAt != 0L) DiagLog.event("launcher", "back") }

    fun dismissNote() { note = null }

    /** MemAvailable from /proc/meminfo, in MiB (no permission needed); null where it cannot be read */
    fun memAvailableMiB(meminfo: File = File("/proc/meminfo")): Long? = runCatching {
        meminfo.useLines { l -> l.firstOrNull { it.startsWith("MemAvailable:") } }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.let { it / 1024 }
    }.getOrNull()
}
