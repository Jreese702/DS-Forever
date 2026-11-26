package com.jrees.wowcompanion

/**
 * THE BOTTOM SCREEN COMES STRAIGHT BACK (the user, 2026-11-25: "Coming back after a drop: When the bottom screen goes blank or restarts,
 * DS Forever should come straight back on it, in the same room, already caught up with the game, with no app restart"; "Like most of the
 * time the drops happen from minimizing the game or closing my thor to sleep"). A minimize or a sleep PARKS the launcher's bottom window
 * (the same window and CompanionHost, hidden: fork/DsForeverScreen) - nothing here is needed then. When a rebuild cannot be avoided (the
 * display truly gone and back, Android closing the window by itself, a memory release), the old host leaves this small snapshot for the
 * new one: the room it showed, whether the game was in the world, and the reader's place in General.log ([LogTail.Place]); with
 * [DocMemory]'s latest documents the new host opens caught up, without the waiting screen and without the whole-log replay.
 * The user's pick (Q3): the SAME room only while the game still has that room's window open ([roomAfter]), else Home.
 *
 * Process level, pure: no view, no context, no host is held; memory only (never written to disk, never a sign-in field). Also the
 * problem report's line ([line]): what the bottom screen did this run (kept, shown again, rebuilt and why, the catch-up time).
 */
object BottomSync {
    /**
     * [at] uptime ms; [gameRun] LauncherHooks.gameStartSec (another game run = no restore); [room] a Room's name; [inWorld] a current
     * document in the last [IN_WORLD_MS]; [place] the old reader's place (joined at that host's end, [place]); [why] for the log
     */
    data class Snapshot(val at: Long, val gameRun: Long, val room: String, val inWorld: Boolean, val place: LogTail.Place?, val why: String)

    /** a snapshot older than this restores nothing (the room, the waiting screen) */
    const val RESTORE_MAX_MS = 30 * 60_000L
    /** the game counts as in the world with a current document this recent */
    const val IN_WORLD_MS = 2 * 60_000L

    @Volatile var last: Snapshot? = null
        private set

    /** a host is about to go (Android's close, a memory release, a move, a stop): its snapshot is the one a new host takes */
    fun keep(s: Snapshot?) { if (s != null) last = s }

    /** that host's reader stopped at [p] (its onDestroy): joined to the snapshot it left, for the same game run only */
    fun place(p: LogTail.Place?, gameRun: Long) {
        val s = last ?: return
        if (p != null && s.gameRun == gameRun) last = s.copy(place = p)
    }

    fun fresh(s: Snapshot?, now: Long, gameRun: Long): Boolean = s != null && s.gameRun == gameRun && now - s.at < RESTORE_MAX_MS

    /** the new host's restore: the snapshot when [fresh] (once; a stale one is dropped), else null */
    fun take(now: Long, gameRun: Long): Snapshot? {
        val s = last
        last = null
        return s?.takeIf { fresh(it, now, gameRun) }
    }

    /**
     * Q3 (the user's pick, 2026-11-25: "after a real rebuild it reopens the same room only if the game still has that room's window
     * open, otherwise Home"): the saved room when the capture's holder [holder] (the first CURRENT interact document's) holds the
     * controller for it ([roomsOf] = HomeBack.roomsOf) - the game's window is still open, so the room is adopted with no tap - else null
     * (Home)
     */
    fun roomAfter(saved: String?, holder: String, roomsOf: (String) -> Set<String>): String? =
        saved?.takeIf { it != "HOME" && holder.isNotEmpty() && it in roomsOf(holder) }

    /** the waiting screen starts GONE on a restore only when the game was in the world and is still up (its own rules bring it back) */
    fun waitingGone(s: Snapshot?, gameUp: Boolean): Boolean = s != null && s.inWorld && gameUp

    fun inWorld(lastCurrentAt: Long, now: Long): Boolean = lastCurrentAt > 0L && now - lastCurrentAt < IN_WORLD_MS

    /** the game ended (DsForeverScreen.detachGame, not a relaunch): nothing of it is restored */
    fun drop() { last = null }

    private fun words(ms: Long): String = if (ms < 60_000L) "${ms / 1000} s" else "${ms / 60_000} min"

    fun clear() {
        last = null
        kept = 0; shown = 0; lastAwayMs = -1L; lastWorkMs = -1L; lastCatchUpMs = -1L; rebuilt = 0; releases = 0; looks = 0
        synchronized(why) { why.clear() }
    }

    // ---- the problem report's line

    @Volatile private var kept = 0
    @Volatile private var shown = 0
    @Volatile private var lastAwayMs = -1L
    @Volatile private var lastWorkMs = -1L
    @Volatile private var lastCatchUpMs = -1L
    @Volatile private var rebuilt = 0
    @Volatile private var releases = 0
    @Volatile private var looks = 0
    private val why = LinkedHashMap<String, Int>()

    fun parked() { kept++ }
    /** shown again after [awayMs] away, [workMs] of our own work for it */
    fun back(awayMs: Long, workMs: Long) { shown++; lastAwayMs = awayMs; lastWorkMs = workMs }
    /** the first current document after a return / a restore, [ms] after it */
    fun caughtUp(ms: Long) { lastCatchUpMs = ms }
    fun rebuilt(reason: String) { rebuilt++; synchronized(why) { why[reason] = (why[reason] ?: 0) + 1 } }
    fun released() { releases++ }
    fun reopenLook() { looks++ }

    fun line(): String {
        val sb = StringBuilder("Bottom screen: kept $kept, shown again $shown")
        if (shown > 0) sb.append(" (the last after ${words(lastAwayMs.coerceAtLeast(0L))} away, $lastWorkMs ms of ours" +
            (if (lastCatchUpMs >= 0) ", caught up in $lastCatchUpMs ms" else "") + ")")
        sb.append(", rebuilt $rebuilt")
        val w = synchronized(why) { why.entries.joinToString { "${it.key} ${it.value}" } }
        if (w.isNotEmpty()) sb.append(" ($w)")
        if (releases > 0) sb.append(", let go for memory $releases")
        if (looks > 0) sb.append(", reopen looks $looks")
        return sb.toString()
    }
}
