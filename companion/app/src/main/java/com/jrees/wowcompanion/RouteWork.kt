package com.jrees.wowcompanion

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * THE ROUTE'S WORK OFF THE UI THREAD (test56's ANRs: "Input dispatching timed out ... MapView.onDraw -> dangerLeg -> dangerGrid ->
 * DangerGrid.build -> Roads.isRoad -> Roads.of -> FileInputStream"). One background thread for the route's grids, its A* and the
 * zone files they read; the views draw only finished results and are told when a new one is ready. [Files] keeps each zone file in
 * memory once loaded, and remembers a missing one for [MISS_RETRY_MS] (a missing file was opened again for every cell, under a lock).
 */
object RouteWork {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "route-work").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 } }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** [work] on the route thread, then [done] with its result on the UI thread */
    fun <T> run(work: () -> T, done: (T) -> Unit) {
        exec.execute {
            val r = runCatching(work)
            r.onFailure { android.util.Log.w("WCMAP", "route work failed: ${it.message}") }
            r.getOrNull()?.let { v -> main.post { done(v) } }
        }
    }

    fun onMain(): Boolean = mainNow()

    // the tests' seams (the app never sets them): the UI-thread check and the miss clock (no Looper / SystemClock on the JVM)
    @Volatile internal var mainNow: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() }
    @Volatile internal var elapsed: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    /** [work] on the route thread after what is queued there, no result (Light 8: a relief lets the route thread's own scratch go) */
    fun later(work: () -> Unit) { exec.execute { runCatching(work) } }

    const val MISS_RETRY_MS = 10_000L

    /**
     * one kind of zone file, kept in memory: [get] never touches the disk on the UI thread (a miss there asks the route thread to load
     * it and answers null; the load bumps [version] and calls the listeners); off the UI thread it loads at once.
     * Memory work #56 (D1, the user's yes 2026-11-19): at most [max] kept, and when one more comes the one to go is the least recently
     * used that is NOT in [keep] (the zone the player is in and its neighbours, [KeepLru]); [sizeOf] says a value's bytes for the memory
     * line ([bytes]) and [trimTo] lets go under pressure.
     */
    class Files<T : Any>(max: Int = 0, private val keep: () -> Collection<Int> = { emptyList() }, private val sizeOf: (T) -> Long = { 0L },
                         bound: (() -> Int)? = null, others: () -> Int = { -1 }, playerFirst: () -> Boolean = { false },
                         private val holdJob: String? = null, private val holdIf: (Int) -> Boolean = { false },
                         private val quietReload: () -> Boolean = { false },
                         private val load: (Int) -> T?) {
        // at most [max] kept (the least recently used not kept by [keep] goes first; 0 = no bound): a zone's lattice is 5-20 MB.
        // Light 8 (LIGHT8_PLAN.md): [bound] instead of [max] when given, read at every put (DeviceTier.light8 live, never once here);
        // [others] at most that many outside [keep] (-1 = no such rule); [playerFirst] the player's zone ([keep]'s first) goes last
        private val have = KeepLru<T>(bound ?: { max }, { runCatching { keep() }.getOrDefault(emptyList()) }, others, playerFirst)
        private val missAt = HashMap<Int, Long>()
        private val loading = HashSet<Int>()
        // Light 8's load-time hold (LoadHold, [holdJob]): the maps asked for while it held them, loaded when it ends
        private val heldMaps = LinkedHashSet<Int>()
        private val holdKey = "files@" + System.identityHashCode(this)
        @Volatile var version = 0; private set
        // Light 8 (the 8 GB Thors' bound, [quietReload]): the maps stored since their last [drop]. A map the bound (or a relief) let go
        // and read again at once for a caller off the UI thread is the same file the caller now has: its load does not bump [version],
        // so a plan keyed by the versions (MapView's cross-zone route, SafePath's stamps) settles as on test build 1516's unbounded
        // caches instead of planning again after every reload. A caller that got null (the UI thread's miss, the load-time hold, a
        // load that failed - an IOException or an OutOfMemoryError - or the miss window after it) still sees the bump when its map arrives
        private val stored = HashSet<Int>()
        private val listeners = ArrayList<() -> Unit>()
        @Synchronized fun onLoaded(l: () -> Unit) { listeners.add(l) }

        fun get(map: Int): T? = get(map, true)

        /** [get]; [hold] false = never waits for Light 8's load-time hold (SafePath's draft reading its neighbours' drafts) */
        fun get(map: Int, hold: Boolean): T? {
            synchronized(this) {
                have.get(map)?.let { return it }
                missAt[map]?.let { if (elapsed() - it < MISS_RETRY_MS) { stored.remove(map); return null } }    // (null: its arrival bumps)
                if (hold && held(map)) return null
                if (onMain()) {
                    stored.remove(map)                                 // (the caller got null: its arrival bumps [version])
                    if (loading.add(map)) exec.execute { fill(map) }
                    return null
                }
            }
            return fill(map)
        }

        /** [map] when it is in memory now, never a load (any thread) */
        @Synchronized fun peek(map: Int): T? = have.peek(map)

        private fun fill(map: Int): T? {
            val v = runCatching { load(map) }.getOrNull()
            val ls: List<() -> Unit>
            synchronized(this) {
                loading.remove(map)
                // (a failed load: the caller got null, so the map's arrival bumps [version] - an off-UI caller that does not retry,
                // SafePath's draft, plans again then)
                if (v != null) store(map, v) else { missAt[map] = elapsed(); stored.remove(map) }
                ls = if (v != null) ArrayList(listeners) else emptyList()
            }
            if (ls.isNotEmpty()) main.post { ls.forEach { it() } }
            return v
        }

        /** [v] kept for [map] (a load's result; the tests put one directly): what went for the bound */
        @Synchronized internal fun store(map: Int, v: T): List<Int> {
            val gone = have.put(map, v); missAt.remove(map)
            // (every other Thor, and every first load or rebuilt file: test build 1516's bump)
            if (!(map in stored && runCatching { quietReload() }.getOrDefault(false))) version++
            stored.add(map)
            return gone
        }

        /** Light 8: [map] waits for the load-time hold (LoadHold: a zone other than the player's while the game loads); off Light 8,
         *  or without a [holdJob], never */
        private fun held(map: Int): Boolean {
            val job = holdJob ?: return false
            if (!runCatching { holdIf(map) }.getOrDefault(false)) return false
            if (!LoadHold.hold(job, holdKey) { loadHeld() }) return false
            heldMaps.add(map); stored.remove(map)                  // (the caller got null: its arrival bumps [version])
            return true
        }
        /** the hold is over: the maps asked for meanwhile are loaded now (the route thread; each load bumps [version]). How many */
        fun loadHeld(): Int {
            val ms = synchronized(this) { heldMaps.filter { have.peek(it) == null && loading.add(it) }.also { heldMaps.clear() } }
            for (m in ms) exec.execute { fill(m) }
            return ms.size
        }
        /** the maps waiting for the hold now (the tests) */
        @Synchronized fun heldNow(): List<Int> = heldMaps.toList()
        /** the bound now (0 = none; read live) */
        @Synchronized fun bound(): Int = have.bound()

        /** forget [map] (its file was rebuilt) */
        @Synchronized fun drop(map: Int) { have.remove(map); missAt.remove(map); stored.remove(map) }
        /** forget them all (the device short of memory: loaded again when asked) */
        @Synchronized fun trim() { have.clear() }
        /** memory pressure: every one but [stay] goes; the bytes let go ([sizeOf]) */
        @Synchronized fun trimTo(stay: Collection<Int>): Long = have.dropAllBut(stay).sumOf { runCatching { sizeOf(it) }.getOrDefault(0L) }
        /** how many are kept now */
        @Synchronized fun kept(): Int = have.size
        /** the maps kept now (the least recently used first) */
        @Synchronized fun keys(): List<Int> = have.keys()
        /** the bytes kept now ([sizeOf]; the memory line's) */
        @Synchronized fun bytes(): Long = have.values().sumOf { runCatching { sizeOf(it) }.getOrDefault(0L) }
    }

    /**
     * AT MOST [max] VALUES BY MAP, THE LEAST RECENTLY USED GOING FIRST - BUT NEVER ONE [keep] NAMES WHILE ANOTHER CAN GO (memory work #56,
     * D1: the in-zone route's zone and its neighbours stay while the cross-zone legs read other zones through the same cache). When every
     * one is kept the least recently used goes anyway, except the one just put (the bound holds). [max] 0 = no bound. Pure (the tests).
     */
    class KeepLru<T : Any>(private val maxNow: () -> Int, private val keep: () -> Collection<Int> = { emptyList() },
                           private val othersNow: () -> Int = { -1 }, private val playerFirst: () -> Boolean = { false }) {
        constructor(max: Int, keep: () -> Collection<Int> = { emptyList() }) : this({ max }, keep)
        private val m = LinkedHashMap<Int, T>(16, 0.75f, true)
        val size get() = m.size
        fun get(k: Int): T? = m[k]
        /** without touching its place in the order */
        fun peek(k: Int): T? = m.entries.firstOrNull { it.key == k }?.value
        fun remove(k: Int): T? = m.remove(k)
        fun clear() = m.clear()
        fun keys(): List<Int> = m.keys.toList()
        fun values(): List<T> = m.values.toList()
        /** the bound now (read live; 0 = none) */
        fun bound(): Int = runCatching { maxNow() }.getOrDefault(0)
        /** [k] -> [v]; what had to go for the bound, the least recently used not kept first. Light 8: at most [othersNow] outside the
         *  keep set (the just put one stays), and with [playerFirst] the keep set's first (the player's zone) goes last of all */
        fun put(k: Int, v: T): List<Int> {
            m[k] = v
            val max = bound()
            val others = runCatching { othersNow() }.getOrDefault(-1)
            if ((max <= 0 || m.size <= max) && others < 0) return emptyList()
            val order = keep().toList()
            val stay = order.toSet()
            val player = if (runCatching { playerFirst() }.getOrDefault(false)) order.firstOrNull() else null
            val gone = ArrayList<Int>()
            if (others >= 0) while (m.keys.count { it !in stay } > others) {
                val victim = m.keys.firstOrNull { it != k && it !in stay } ?: break
                m.remove(victim); gone.add(victim)
            }
            while (max > 0 && m.size > max) {
                val victim = m.keys.firstOrNull { it != k && it !in stay } ?: m.keys.firstOrNull { it != k && it != player }
                    ?: m.keys.firstOrNull { it != k } ?: break
                m.remove(victim); gone.add(victim)
            }
            return gone
        }
        /** every one but [stay] goes; the values that went */
        fun dropAllBut(stay: Collection<Int>): List<T> {
            val s = stay.toSet()
            val out = ArrayList<T>()
            val it = m.entries.iterator()
            while (it.hasNext()) { val e = it.next(); if (e.key !in s) { out.add(e.value); it.remove() } }
            return out
        }
    }

    /** a map keeping at most [max] entries, the least recently used dropped first */
    class Lru<K, V>(private val max: Int) : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
    }
}
