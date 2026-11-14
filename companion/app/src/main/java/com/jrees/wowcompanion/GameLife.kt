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
        startedAt = now; worldSeen = false; ended = false; note = null; closer = null
        DiagLog.event("game", "started")
        LastRun.gameStarted(now)                                   // 1.0.17: kept across the app's own death
        // issue triage 2026-11-14 (the 1.0.29 - 1.0.31 low-memory cluster; the user: "yes on the triage things"): the game is about to
        // take gigabytes; DS Forever lets go of what is not on show and collects first (nothing when the watch is not running)
        MemWatch.relieve("game start")
    }

    /** 1.0.19: the game has reached the world and not ended (the LB picture only then: the user, "The LB menu is also appearing in the app
     *  startup screen before the game starts") */
    val worldNow: Boolean get() = worldSeen && !ended

    /** the first CURRENT document from the add-on: the game is in the world */
    fun inWorld(now: Long = System.currentTimeMillis()) {
        if (worldSeen || startedAt == 0L) return
        worldSeen = true
        DiagLog.event("game", "in the world after ${minutes(now - startedAt)}")
        LastRun.gameInWorld()
        // issue triage 2026-11-14: the reports' kills came 7 - 36 s after this, the game still loading the world: the same relief.
        // Verification of the fix (the user, 2026-11-13: "yes on the triage things"): the start screen that draws the loading pictures
        // closes on this same document, so a relief at once found them drawn under Scene's 2 s and kept them until its own 15 s idle
        // (no collection then: #250 and #254 were killed 8 s and 7 s after this). [WORLD_RELIEF_MS] later they go, and the one collection
        // frees them
        MemWatch.relieve("world", WORLD_RELIEF_MS)
    }
    /** the world relief's wait: past Scene's [Scene.TRIM_IDLE_MS] after the start screen closes, well before the reports' first kills */
    const val WORLD_RELIEF_MS = Scene.TRIM_IDLE_MS + 500L

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
        note = if (abnormal(status, by)) noteText(status, ran) else null
    }

    /** the launcher's screen is back (the game screen gone) */
    fun launcherBack() { if (startedAt != 0L) DiagLog.event("launcher", "back") }

    fun dismissNote() { note = null }

    /** MemAvailable from /proc/meminfo, in MiB (no permission needed); null where it cannot be read */
    fun memAvailableMiB(meminfo: File = File("/proc/meminfo")): Long? = runCatching {
        meminfo.useLines { l -> l.firstOrNull { it.startsWith("MemAvailable:") } }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.let { it / 1024 }
    }.getOrNull()
}
