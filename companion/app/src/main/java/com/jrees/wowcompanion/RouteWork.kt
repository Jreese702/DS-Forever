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

    fun onMain(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    const val MISS_RETRY_MS = 10_000L

    /**
     * one kind of zone file, kept in memory: [get] never touches the disk on the UI thread (a miss there asks the route thread to load
     * it and answers null; the load bumps [version] and calls the listeners); off the UI thread it loads at once.
     * Memory work #56 (D1, the user's yes 2026-11-19): at most [max] kept, and when one more comes the one to go is the least recently
     * used that is NOT in [keep] (the zone the player is in and its neighbours, [KeepLru]); [sizeOf] says a value's bytes for the memory
     * line ([bytes]) and [trimTo] lets go under pressure.
     */
    class Files<T : Any>(max: Int = 0, private val keep: () -> Collection<Int> = { emptyList() }, private val sizeOf: (T) -> Long = { 0L },
                         private val load: (Int) -> T?) {
        // at most [max] kept (the least recently used not kept by [keep] goes first; 0 = no bound): a zone's lattice is 5-20 MB
        private val have = KeepLru<T>(max) { runCatching { keep() }.getOrDefault(emptyList()) }
        private val missAt = HashMap<Int, Long>()
        private val loading = HashSet<Int>()
        @Volatile var version = 0; private set
        private val listeners = ArrayList<() -> Unit>()
        @Synchronized fun onLoaded(l: () -> Unit) { listeners.add(l) }

        fun get(map: Int): T? {
            synchronized(this) {
                have.get(map)?.let { return it }
                val now = android.os.SystemClock.elapsedRealtime()
                missAt[map]?.let { if (now - it < MISS_RETRY_MS) return null }
                if (onMain()) {
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
                if (v != null) { have.put(map, v); missAt.remove(map); version++ } else missAt[map] = android.os.SystemClock.elapsedRealtime()
                ls = if (v != null) ArrayList(listeners) else emptyList()
            }
            if (ls.isNotEmpty()) main.post { ls.forEach { it() } }
            return v
        }

        /** forget [map] (its file was rebuilt) */
        @Synchronized fun drop(map: Int) { have.remove(map); missAt.remove(map) }
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
    class KeepLru<T : Any>(private val max: Int, private val keep: () -> Collection<Int> = { emptyList() }) {
        private val m = LinkedHashMap<Int, T>(16, 0.75f, true)
        val size get() = m.size
        fun get(k: Int): T? = m[k]
        /** without touching its place in the order */
        fun peek(k: Int): T? = m.entries.firstOrNull { it.key == k }?.value
        fun remove(k: Int): T? = m.remove(k)
        fun clear() = m.clear()
        fun keys(): List<Int> = m.keys.toList()
        fun values(): List<T> = m.values.toList()
        /** [k] -> [v]; what had to go for the bound, the least recently used not kept first */
        fun put(k: Int, v: T): List<Int> {
            m[k] = v
            if (max <= 0 || m.size <= max) return emptyList()
            val stay = keep().toSet()
            val gone = ArrayList<Int>()
            while (m.size > max) {
                val victim = m.keys.firstOrNull { it != k && it !in stay } ?: m.keys.firstOrNull { it != k } ?: break
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
