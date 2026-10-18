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
    /** the launcher's note after an abnormal end; null = none */
    @Volatile var note: String? = null
        private set

    /**
     * The guest program's exit status in words: 0 = closed normally; a signal (128 + n, as a shell reports it, or a negative n)
     * names it, with what Android's memory killer looks like (SIGKILL); anything else is the program's own error status.
     */
    fun describe(status: Int): String {
        val sig = when { status > 128 && status < 160 -> status - 128; status < 0 -> -status; else -> 0 }
        return when {
            status == 0 -> "closed normally"
            sig == 9 -> "was killed (signal 9: Android may have needed the memory)"
            sig == 11 -> "crashed (signal 11)"
            sig == 6 -> "aborted (signal 6)"
            sig > 0 -> "was stopped by signal $sig"
            else -> "ended with error status $status"
        }
    }

    /** an end the launcher should offer a report for */
    fun abnormal(status: Int): Boolean = status != 0

    fun minutes(ms: Long): String = if (ms < 60_000) "${ms / 1000} s" else "${ms / 60_000} min"

    /** the note's words */
    fun noteText(status: Int, ranMs: Long): String =
        "The game ${describe(status)} after ${minutes(ranMs)}. If that wasn't you, a problem report helps us find out why."

    // ------------------------------------------------------------------ the events (the launcher and the bottom screen call these)

    fun started(now: Long = System.currentTimeMillis()) {
        startedAt = now; worldSeen = false; ended = false; note = null
        DiagLog.event("game", "started")
    }

    /** the first CURRENT document from the add-on: the game is in the world */
    fun inWorld(now: Long = System.currentTimeMillis()) {
        if (worldSeen || startedAt == 0L) return
        worldSeen = true
        DiagLog.event("game", "in the world after ${minutes(now - startedAt)}")
    }

    /** the guest program ended with [status] (XServerScreen's termination callback) */
    fun ended(status: Int, now: Long = System.currentTimeMillis()) {
        if (ended) return
        ended = true
        val ran = if (startedAt == 0L) 0L else now - startedAt
        val mem = memAvailableMiB()?.let { "$it MiB" } ?: "unknown"
        val indoor = runCatching { IndoorSchedulerStep.status?.let { "${it.phase.name.lowercase()} ${it.ready}/${it.total}" } }.getOrNull() ?: "not running"
        DiagLog.event("game", "ended: ${describe(status)} (status $status) after ${minutes(ran)}${if (worldSeen) "" else ", before reaching the world"}; " +
            "MemAvailable $mem; indoor maps: $indoor")
        note = if (abnormal(status)) noteText(status, ran) else null
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
