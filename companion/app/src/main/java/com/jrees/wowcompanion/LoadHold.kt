package com.jrees.wowcompanion

/**
 * LIGHT 8'S LOAD-TIME HOLDS (LIGHT8_PLAN.md change 4; the 8 GB Thors only, DeviceTier.light8 read live). Two jobs wait while the game
 * loads, so DS Forever adds no spike of its own when the game's memory jumps:
 *  - the danger map re-read (DangerStore: the QuestieDB read);
 *  - SafePath data for zones other than the player's own (SafePath's and ShippedNets' zone files, the cross-zone graph).
 * The windows ([deferNow]): from the game's start (Play) until it reaches the world (at most [HARD_MS] from Play), from the world
 * entry (and its return after a logout) until 30 s after it - its own window, so a slow login still holds through the kills 7 - 36 s
 * after the world - and from each loading screen in the world until 5 s after it ends (at most [HARD_MS] from its start); each job's
 * own wait at most [HARD_MS], so nothing can stay held (a lost "loading over" event, a world that never comes). That cap counts from
 * the job's FIRST hold and a new window never re-bases it (the plan: "Each wait has a hard limit of 90 s"): a job held since Play on
 * a login slower than about 60 s runs at its 90 s inside the world's 30 s window (and is not held again in that window); only the jobs
 * first asked after the world entry wait for its end. Nothing once the game
 * has ended. The minimap is never held. Each hold and each run is one mem.log line
 * ("light8: held ...", "light8: ran ... after N s held"). Off Light 8 nothing is ever held (test build 1516's behaviour).
 */
object LoadHold {
    /** the login window lasts until this long after the world is reached */
    const val LOGIN_TAIL_MS = 30_000L
    /** a loading screen's window lasts until this long after it ends */
    const val ZONE_TAIL_MS = 5_000L
    /** the hard limit of every window and of every job's wait */
    const val HARD_MS = 90_000L
    /** how often a held job is looked at while one waits */
    const val TICK_MS = 1_000L

    const val DANGER = "danger re-read"
    const val SAFEPATH = "other-zone SafePath"

    /**
     * the holds apply at [now] (epoch ms; pure): from [gameStartedAt] (0 = no game, or it ended) until [inWorldAt] (0 = not yet), at
     * most [HARD_MS]; from [inWorldAt] (the world's entry or return) until [LOGIN_TAIL_MS] after it, while a game runs; from
     * [loadStartAt] (the last loading screen in the world, 0 = none, or the game ended) while [loadingOn] and until [loadEndAt] +
     * [ZONE_TAIL_MS], at most [HARD_MS] from its start
     */
    fun deferNow(gameStartedAt: Long, inWorldAt: Long, loadStartAt: Long, loadingOn: Boolean, loadEndAt: Long, now: Long): Boolean {
        val login = gameStartedAt > 0L && now >= gameStartedAt && now - gameStartedAt < HARD_MS && (inWorldAt <= 0L || now < inWorldAt)
        val world = gameStartedAt > 0L && inWorldAt > 0L && now >= inWorldAt && now < inWorldAt + LOGIN_TAIL_MS
        val zone = loadStartAt > 0L && now >= loadStartAt && now - loadStartAt < HARD_MS &&
            (loadingOn || now < loadEndAt + ZONE_TAIL_MS)
        return login || world || zone
    }

    // the tests' seams (the app never sets them)
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }
    @Volatile internal var lightNow: () -> Boolean = { DeviceTier.light8() }
    @Volatile internal var windowNow: (Long) -> Boolean = { now -> GameLife.holdWindow(now) }
    @Volatile internal var playerNow: () -> Int = { LowMemCuts.playerZone() }
    @Volatile internal var say: (String) -> Unit = { l -> MemWatch.note(l) }

    /** Light 8 and a loading window now */
    fun holdingNow(now: Long = clock()): Boolean = runCatching { lightNow() && windowNow(now) }.getOrDefault(false)

    /** [map] is not the player's zone (a known one: while the player's is not known, no zone is held) */
    fun otherZone(map: Int): Boolean { val p = runCatching { playerNow() }.getOrDefault(0); return p != 0 && map != p }

    private class Job(val heldAt: Long) { val then = LinkedHashMap<String, () -> Unit>() }
    private val pending = LinkedHashMap<String, Job>()
    /** the jobs let go by their own hard limit while the window still runs: never held again until it ends (a tick that sees no
     *  window, a hold asked outside one, or a new window: [windowStarted]) */
    private val overLimit = HashSet<String>()

    /**
     * [job] (one of its parts: [key]) asked to run now: true = held (the caller skips it; [then] runs when the hold ends, the newest
     * [then] of a key), false = run it now (no hold, or this job already waited [HARD_MS] in this window). Any thread.
     */
    fun hold(job: String, key: String = job, then: () -> Unit): Boolean {
        val now = clock()
        if (!holdingNow(now)) { synchronized(this) { overLimit.clear() }; return false }
        val first: Boolean
        synchronized(this) {
            if (job in overLimit) return false
            val j = pending[job]
            if (j != null && now - j.heldAt >= HARD_MS) return false
            first = j == null
            (j ?: Job(now).also { pending[job] = it }).then[key] = then
        }
        if (first) { runCatching { say("light8: held $job (the game is loading)") }; arm() }
        return true
    }

    /**
     * a new window starts (GameLife: Play, the world's entry or return, a loading screen): a job its own limit let go in an earlier
     * window is held again in this one. Any thread; off Light 8 there is nothing to clear
     */
    @Synchronized fun windowStarted() { overLimit.clear() }

    /** the jobs let go by their own limit, not held again in this window (the tests) */
    @Synchronized internal fun overLimitNow(): List<String> = overLimit.toList()

    /** the jobs held now (the tests, the report) */
    @Synchronized fun heldJobs(): List<String> = pending.keys.toList()

    /**
     * a look at the held jobs ([TICK_MS] while any waits; the tests call it): a job runs once the window is over, or once it waited
     * [HARD_MS]. The jobs run now (their names)
     */
    fun tick(now: Long = clock()): List<String> {
        val holding = holdingNow(now)
        val go = synchronized(this) {
            if (!holding) overLimit.clear()
            val g = pending.entries.filter { !holding || now - it.value.heldAt >= HARD_MS }.map { it.key to it.value }
            for ((n, _) in g) { pending.remove(n); if (holding) overLimit.add(n) }
            g
        }
        for ((name, j) in go) {
            runCatching { say(ranLine(name, now - j.heldAt, !holding)) }
            for (f in j.then.values) runCatching { f() }
        }
        return go.map { it.first }
    }

    /** "light8: ran danger re-read after 34 s held" (+ " (90 s limit)" when the limit let it go). Pure */
    fun ranLine(job: String, heldMs: Long, windowOver: Boolean): String =
        "light8: ran $job after ${heldMs.coerceAtLeast(0L) / 1000} s held" + if (windowOver) "" else " (${HARD_MS / 1000} s limit)"

    /** tests: nothing held */
    @Synchronized internal fun reset() { pending.clear(); overLimit.clear(); armed = false }

    @Volatile private var armed = false
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val ticker = object : Runnable {
        override fun run() {
            runCatching { tick() }
            // (the ticker also runs while a job its limit let go waits for the window's end: the tick that sees it over clears it)
            val more = synchronized(this@LoadHold) { (pending.isNotEmpty() || overLimit.isNotEmpty()).also { if (!it) armed = false } }
            if (more) runCatching { main.postDelayed(this, TICK_MS) }.onFailure { armed = false }
        }
    }
    private fun arm() {
        synchronized(this) { if (armed) return; armed = true }
        runCatching { main.postDelayed(ticker, TICK_MS) }.onFailure { armed = false }    // (the off-device tests: no main thread)
    }
}
