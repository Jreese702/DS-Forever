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
     * [at] SystemClock.elapsedRealtime ms (the review: it counts deep sleep - uptime stops while the Thor sleeps, so a nine-hour sleep read
     * as seconds); [gameRun] LauncherHooks.gameStartSec (another game run = no restore; a configuration relaunch keeps it: [attached]);
     * [room] a Room's name; [inWorld] a current document in the last [IN_WORLD_MS]; [place] the old reader's place (joined at that host's
     * end, [place]); [why] for the log
     */
    data class Snapshot(val at: Long, val gameRun: Long, val room: String, val inWorld: Boolean, val place: LogTail.Place?, val why: String)

    /**
     * A HOST'S SNAPSHOT (CompanionHost.snapshot, pure). The review: a host that was itself restored and has not settled yet ([settled]
     * false: no current document since it came) carries the restore forward - its room still waiting for the adopt rule ([restoreRoom]),
     * whether the game was in the world, the old snapshot's time, and the reader's place when its own reader has none yet - so a second
     * rebuild soon after the first (Android closing the window again at a wake, a move, the verify) never turns the restore into Home, the
     * waiting screen and the whole-log replay. Settled: what the host shows now ([room], [inWorldNow], its reader's [place])
     */
    fun snapshotOf(restored: Snapshot?, settled: Boolean, room: String, restoreRoom: String?, inWorldNow: Boolean, place: LogTail.Place?,
                   now: Long, gameRun: Long, why: String): Snapshot {
        val r = restored?.takeIf { !settled }
        return Snapshot(r?.at ?: now, gameRun, restoreRoom ?: room, r?.inWorld ?: inWorldNow, place ?: r?.place, why)
    }

    /**
     * THE GAME RUN ACROSS A CONFIGURATION RELAUNCH (the review: a reopen from the bottom grid moves the activity, Android makes it again,
     * and the new activity's game screen stamps LauncherHooks.gameStartSec anew - the snapshot the old activity kept was then another
     * run's and was thrown away). DsForeverScreen.stop marks the relaunch with the game running ([relaunching]: its run, when); the next
     * game screen ([attached], within [RELAUNCH_MS]) is the SAME game: the kept snapshot is re-keyed to the new stamp. True = that same
     * game (nothing of it is dropped); false = a new run
     */
    const val RELAUNCH_MS = 30_000L
    @Volatile private var relaunchRun = 0L
    @Volatile private var relaunchAt = 0L
    fun relaunching(run: Long, now: Long) { relaunchRun = run; relaunchAt = now }
    fun attached(run: Long, now: Long): Boolean {
        val was = relaunchRun; val at = relaunchAt
        relaunchRun = 0L; relaunchAt = 0L
        if (was <= 0L || now - at !in 0L until RELAUNCH_MS) return false
        last?.let { s -> if (s.gameRun == was) last = s.copy(gameRun = run) }
        return true
    }

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

    /**
     * the waiting screen starts GONE on a restore only when the game was in the world, is still up, and the snapshot is recent ([now] -
     * [Snapshot.at] under [IN_WORLD_MS], elapsedRealtime: a long sleep counts - WoW may have dropped the connection). Its own rules bring
     * it back; an older one shows it until the first current document
     */
    fun waitingGone(s: Snapshot?, gameUp: Boolean, now: Long): Boolean = s != null && s.inWorld && gameUp && now - s.at in 0L until IN_WORLD_MS

    fun inWorld(lastCurrentAt: Long, now: Long): Boolean = lastCurrentAt > 0L && now - lastCurrentAt < IN_WORLD_MS

    /** the game ended (DsForeverScreen.detachGame, not a relaunch): nothing of it is restored */
    fun drop() { last = null }

    private fun words(ms: Long): String = if (ms < 60_000L) "${ms / 1000} s" else "${ms / 60_000} min"

    fun clear() {
        last = null
        kept = 0; shown = 0; lastAwayMs = -1L; lastWorkMs = -1L; lastCatchUpMs = -1L; rebuilt = 0; releases = 0; looks = 0; hosts = 0
        relaunchRun = 0L; relaunchAt = 0L
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
    @Volatile private var hosts = 0
    private val why = LinkedHashMap<String, Int>()

    fun parked() { kept++ }
    /** shown again after [awayMs] away, [workMs] of our own work for it (its catch-up time is its own: none until [caughtUp]) */
    fun back(awayMs: Long, workMs: Long) { shown++; lastAwayMs = awayMs; lastWorkMs = workMs; lastCatchUpMs = -1L }
    /** the first current document after a return / a restore, [ms] after it */
    fun caughtUp(ms: Long) { lastCatchUpMs = ms }
    fun rebuilt(reason: String) { rebuilt++; lastCatchUpMs = -1L; synchronized(why) { why[reason] = (why[reason] ?: 0) + 1 } }
    private fun unrebuilt(reason: String) {
        if (rebuilt > 0) rebuilt--
        synchronized(why) { val n = (why[reason] ?: 0) - 1; if (n > 0) why[reason] = n else why.remove(reason) }
    }

    /**
     * a launcher host came (CompanionHost.onCreate, [restored]: the snapshot it took, null = none). The review: every host after the
     * run's first is a rebuild in the report - restored ones by their snapshot's reason, the others as "no snapshot" (an old snapshot,
     * another game run, Android's close with the game not up) - so "rebuilt 0" means none
     */
    fun hostMade(restored: Snapshot?) {
        hosts++
        if (restored != null) rebuilt(restored.why) else if (hosts > 1) rebuilt(NO_SNAPSHOT)
    }

    /**
     * a host whose window never came up (the window manager refused it: HostPresentation / TopWindow abandon). The review: it is not
     * counted, and the snapshot it took is put back for the watchdog's next try (never used up by a failed open)
     */
    fun hostAbandoned(restored: Snapshot?) {
        if (hosts > 0) hosts--
        if (restored != null) { unrebuilt(restored.why); if (last == null) last = restored }
        else if (hosts >= 1) unrebuilt(NO_SNAPSHOT)
    }
    const val NO_SNAPSHOT = "no snapshot"
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
