package com.jrees.wowcompanion

/**
 * THE BOTTOM SCREEN COMES STRAIGHT BACK (the user, 2026-11-25: "Coming back after a drop: When the bottom screen goes blank or restarts,
 * DS Forever should come straight back on it, in the same room, already caught up with the game, with no app restart"; "Like most of the
 * time the drops happen from minimizing the game or closing my thor to sleep"). A minimize or a sleep PARKS the launcher's bottom window
 * (the same window and CompanionHost, hidden: fork/DsForeverScreen) - nothing here is needed then. When a rebuild cannot be avoided (the
 * display truly gone and back, Android closing the window by itself, a memory release, a reopen from Cocoon's bottom grid that relaunches
 * the activity), the old host leaves this small snapshot for the new one: the room it showed, whether the game was in the world, the
 * reader's place in General.log ([LogTail.Place]) and what it lays back with no tap ([HostUi]: Home's open layer, the room's tab and
 * scroll, Edit Home's session); with [DocMemory]'s latest documents the new host opens caught up, without the waiting screen and
 * without the whole-log replay. The user's pick (Q3): the SAME room only while the game still has that room's window open
 * ([roomAfter]), else Home.
 *
 * Process level, pure: no view, no context, no host is held; memory only (never written to disk, never a sign-in field). Also the
 * problem report's line ([line]): what the bottom screen did this run (kept, shown again, rebuilt and why, the catch-up time).
 */
object BottomSync {
    /**
     * [at] SystemClock.elapsedRealtime ms (the review: it counts deep sleep - uptime stops while the Thor sleeps, so a nine-hour sleep read
     * as seconds); [gameRun] LauncherHooks.gameStartSec (another game run = no restore; a configuration relaunch keeps it: [attached]);
     * [room] a Room's name; [inWorld] a current document in the last [IN_WORLD_MS] (of the PARK, for a parked host: round 2); [place]
     * the old reader's place (joined at that host's end, [place]); [why] for the log
     */
    data class Snapshot(val at: Long, val gameRun: Long, val room: String, val inWorld: Boolean, val place: LogTail.Place?, val why: String,
                        /** SystemClock.uptimeMillis at [at] (round 2: [sleptSince] - the time the Thor was asleep since, not the time away) */
                        val atUp: Long = at,
                        /** the deep sleep a PARKED host had already been through when it was taken (round 2: CompanionHost.snapshot) */
                        val slept: Long = 0L,
                        /** what the new host lays back with no tap (round 2: the relaunch route): [HostUi] */
                        val ui: HostUi = HostUi())

    /**
     * WHAT A HOST MADE AGAIN LAYS BACK (round 2 of the review, 2026-11-26: "Home's open layer, every room's tab and scroll, and the Edit
     * Home draft are lost on every bottom-grid return" - the configuration relaunch a reopen from Cocoon's bottom grid brings; SPEC 4.4
     * step 5's restoreTab / restoreLayer): [homeLayer] Home's open layer (HomeView.layerKeep: a full-screen module, the Keyboard &
     * Trackpad tool), [room] the shown room's own small state (RoomKeep: its tab, page, scroll - laid on it when the room itself comes
     * back, by the adopt rule, with no tap), [edit] Edit Home's live session (HomeEditKeep: the draft, the cursor, the record Discard
     * puts back). Pure data: no view, no context, no host
     */
    data class HostUi(val homeLayer: String? = null, val room: Map<String, String> = emptyMap(), val edit: HomeEditKeep? = null)

    /**
     * A HOST'S SNAPSHOT (CompanionHost.snapshot, pure). The review: a host that was itself restored and has not settled yet ([settled]
     * false: no current document since it came) carries the restore forward - its room still waiting for the adopt rule ([restoreRoom]),
     * whether the game was in the world, the old snapshot's clocks, the room's state with its room, and the reader's place when its own
     * reader has none yet - so a second rebuild soon after the first (Android closing the window again at a wake, a move, the verify)
     * never turns the restore into Home, the waiting screen and the whole-log replay. Settled: what the host shows now ([room],
     * [inWorldNow], its reader's [place], [ui]); [nowUp] / [sleptNow] its uptime and (parked) the sleep it has been through
     */
    fun snapshotOf(restored: Snapshot?, settled: Boolean, room: String, restoreRoom: String?, inWorldNow: Boolean, place: LogTail.Place?,
                   now: Long, gameRun: Long, why: String, nowUp: Long = now, sleptNow: Long = 0L, ui: HostUi = HostUi()): Snapshot {
        val r = restored?.takeIf { !settled }
        return if (r != null) Snapshot(r.at, gameRun, restoreRoom ?: room, r.inWorld, place ?: r.place, why, r.atUp, r.slept,
            if (restoreRoom != null) ui.copy(room = r.ui.room) else ui)
        else Snapshot(now, gameRun, restoreRoom ?: room, inWorldNow, place, why, nowUp, sleptNow, ui)
    }

    /**
     * ROUND 2 (2026-11-26: "A snapshot taken while parked marks the game out of the world after 2 min away"): the deep sleep between
     * [since] (elapsedRealtime) / [sinceUp] (uptime) and [now] / [nowUp] - elapsedRealtime counts deep sleep, uptime does not, so their
     * difference is the time the Thor really slept (a minimize, where the game ran on, is none). Pure
     */
    fun asleep(since: Long, sinceUp: Long, now: Long, nowUp: Long): Long =
        if (since <= 0L) 0L else ((now - since) - (nowUp - sinceUp)).coerceAtLeast(0L)

    /** the deep sleep since [s] was taken, plus what its host had slept before ([Snapshot.slept]) */
    fun sleptSince(s: Snapshot, now: Long, nowUp: Long): Long = s.slept + asleep(s.at, s.atUp, now, nowUp)

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
    /**
     * ROUND 2 (2026-11-26: "During a configuration relaunch the restored host sees the game as not up"): a relaunch is under way - the
     * new activity's DS Forever is up, its game screen not yet ([attached] clears the mark). The restored host made meanwhile counts the
     * game as up ([waitingGone], DocMemory's notes): the same game runs on
     */
    fun relaunchPending(now: Long): Boolean = relaunchRun > 0L && relaunchAt > 0L && now - relaunchAt in 0L until RELAUNCH_MS
    fun attached(run: Long, now: Long): Boolean {
        val was = relaunchRun; val at = relaunchAt
        relaunchRun = 0L; relaunchAt = 0L
        if (was <= 0L || now - at !in 0L until RELAUNCH_MS) return false
        last?.let { s -> if (s.gameRun == was) last = s.copy(gameRun = run) }
        return true
    }

    /** a snapshot older than this restores no room, no layer, no room state, no edit session and no hidden waiting screen (round 2: its
     *  reader's place and the document memory still apply: [take]) */
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

    /**
     * the new host's restore (once): this game run's snapshot WHATEVER ITS AGE, else null. Round 2 (2026-11-26: "After a long sleep with
     * Android's close, the whole log is replayed from byte 0"): nothing about the reader's place goes stale with age (the log's own bytes
     * before it prove it is the same log: LogTail.resumeOffset), so an old one ([fresh] false) keeps the place and the document memory
     * and restores only that - Home, no layer, no room state, no edit session, the waiting screen shown ([Snapshot.inWorld] false)
     */
    fun take(now: Long, gameRun: Long): Snapshot? {
        val s = last
        last = null
        if (s == null || s.gameRun != gameRun) return null
        return if (fresh(s, now, gameRun)) s else s.copy(room = "HOME", inWorld = false, ui = HostUi())
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
     * the waiting screen starts GONE on a restore only when the game was in the world (at the PARK, for a parked host) and is still up,
     * and the Thor has not slept [IN_WORLD_MS] since (round 2, 2026-11-26: the time ASLEEP, [sleptSince] - WoW may have dropped the
     * connection over a long sleep; a minimize of any length, where the game ran on, never shows it). Its own rules bring it back (a
     * reload, the sign-in row); after a long sleep it shows until the first current document. [now] elapsedRealtime, [nowUp] uptime
     */
    fun waitingGone(s: Snapshot?, gameUp: Boolean, now: Long, nowUp: Long): Boolean =
        s != null && s.inWorld && gameUp && now >= s.at && sleptSince(s, now, nowUp) < IN_WORLD_MS

    fun inWorld(lastCurrentAt: Long, now: Long): Boolean = lastCurrentAt > 0L && now - lastCurrentAt < IN_WORLD_MS

    /** the game ended (DsForeverScreen.detachGame, not a relaunch): nothing of it is restored */
    fun drop() { last = null }

    private fun words(ms: Long): String = if (ms < 60_000L) "${ms / 1000} s" else "${ms / 60_000} min"

    fun clear() {
        last = null
        kept = 0; shown = 0; lastAwayMs = -1L; lastWorkMs = -1L; lastCatchUpMs = -1L; rebuilt = 0; releases = 0; looks = 0
        rebuildCatchUpMs = -1L; catchUpIsRebuild = false; expected = null; openFails = 0
        relaunchRun = 0L; relaunchAt = 0L
        flipping = false
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
    /** round 2: the last rebuild's own catch-up time (never printed inside the last return's brackets), and whether the first current
     *  document waited for now is a rebuild's */
    @Volatile private var rebuildCatchUpMs = -1L
    @Volatile private var catchUpIsRebuild = false
    /** round 2: the comeback path that closed the window (the next host is a rebuild: [expectRebuild]), null = none */
    @Volatile private var expected: String? = null
    @Volatile private var openFails = 0
    private val why = LinkedHashMap<String, Int>()

    fun parked() { kept++ }
    /** shown again after [awayMs] away, [workMs] of our own work for it (its catch-up time is its own: none until [caughtUp]) */
    fun back(awayMs: Long, workMs: Long) { shown++; lastAwayMs = awayMs; lastWorkMs = workMs; lastCatchUpMs = -1L; catchUpIsRebuild = false }
    /** the first current document after a return / a restore, [ms] after it (round 2: a rebuild's in its own place in [line]) */
    fun caughtUp(ms: Long) { if (catchUpIsRebuild) rebuildCatchUpMs = ms else lastCatchUpMs = ms }
    fun rebuilt(reason: String) { rebuilt++; rebuildCatchUpMs = -1L; catchUpIsRebuild = true; synchronized(why) { why[reason] = (why[reason] ?: 0) + 1 } }
    private fun unrebuilt(reason: String) {
        if (rebuilt > 0) rebuilt--
        synchronized(why) { val n = (why[reason] ?: 0) - 1; if (n > 0) why[reason] = n else why.remove(reason) }
    }

    /**
     * ROUND 2 (2026-11-26: "toggleSingle closes the window without a snapshot ... A user who turns single screen mode on and off once,
     * with no drop at all, reports rebuilt 2"; Streaming's hosts, Swap screens' returns): a COMEBACK PATH closed the bottom window
     * (Android's own close, the verify, a move, the display gone, a memory release, a relaunch, a host's own start over:
     * fork/DsForeverScreen) - the next host is a rebuild, with or without a snapshot. Nothing else marks it, so the single screen switch,
     * Streaming and Swap screens never count
     */
    fun expectRebuild(reason: String) { expected = reason }

    /**
     * a launcher host came (CompanionHost.onCreate, [restored]: the snapshot it took, null = none): a rebuild in the report when a
     * comeback path closed the window before it ([expectRebuild]) - by its snapshot's reason, or "no snapshot" (the game not up, another
     * game run) - so "rebuilt 0" means none. Returns the mark it took (a failed open gives it back: [hostAbandoned])
     */
    fun hostMade(restored: Snapshot?): String? {
        val e = expected
        expected = null
        if (restored != null) rebuilt(restored.why) else if (e != null) rebuilt(NO_SNAPSHOT)
        return e
    }

    /**
     * a host whose window never came up (TopWindow's abandon: the window manager refused it after its onCreate). Not counted, and the
     * snapshot it took and the mark ([mark]: [hostMade]'s) are put back for the watchdog's next try (never used up by a failed open)
     */
    fun hostAbandoned(restored: Snapshot?, mark: String? = null) {
        if (restored != null) { unrebuilt(restored.why); if (last == null) last = restored }
        else if (mark != null) unrebuilt(NO_SNAPSHOT)
        if (mark != null && expected == null) expected = mark
    }
    const val NO_SNAPSHOT = "no snapshot"
    fun released() { releases++ }
    fun reopenLook() { looks++ }
    /** round 2: an open the window manager refused (the report counts them; the watchdog stops after [OPEN_FAILS_MAX] in a row) */
    fun openFailed() { openFails++ }

    fun line(): String {
        val sb = StringBuilder("Bottom screen: kept $kept, shown again $shown")
        if (shown > 0) sb.append(" (the last after ${words(lastAwayMs.coerceAtLeast(0L))} away, $lastWorkMs ms of ours" +
            (if (lastCatchUpMs >= 0) ", caught up in $lastCatchUpMs ms" else "") + ")")
        sb.append(", rebuilt $rebuilt")
        val w = synchronized(why) { why.entries.joinToString { "${it.key} ${it.value}" } }
        if (w.isNotEmpty()) sb.append(" ($w)")
        if (rebuildCatchUpMs >= 0) sb.append(", the last rebuild caught up in $rebuildCatchUpMs ms")
        if (releases > 0) sb.append(", let go for memory $releases")
        if (looks > 0) sb.append(", reopen looks $looks")
        if (openFails > 0) sb.append(", failed opens $openFails")
        return sb.toString()
    }

    /**
     * ROUND 2 (2026-11-26: "Each failed bottom open builds a full CompanionHost ... the watchdog repeats it every 5 s"): this many refused
     * opens in a row and the watchdog stops - the next display event or return tries again (one try each, as before this branch)
     */
    const val OPEN_FAILS_MAX = 5
    fun opensWanted(failsInARow: Int): Boolean = failsInARow < OPEN_FAILS_MAX

    /**
     * ROUND 2 (2026-11-26: "Parking toggles the decor's View visibility, so rooms reset as if left"): true only while a park or a return
     * flips the bottom window's decor (fork/DsForeverScreen's HostPresentation, fork/TopWindow). A room whose visibility handler resets it
     * "as a room left" (Chat's keyboard, menus and cursor; Professions' search) stands aside then: the park keeps every view as it is.
     * Main thread
     */
    @Volatile var flipping = false
        private set
    fun <T> flip(block: () -> T): T { flipping = true; try { return block() } finally { flipping = false } }
}
