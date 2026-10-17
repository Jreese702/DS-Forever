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
     * it and answers null; the load bumps [version] and calls the listeners); off the UI thread it loads at once
     */
    class Files<T : Any>(private val load: (Int) -> T?) {
        private val have = HashMap<Int, T>()
        private val missAt = HashMap<Int, Long>()
        private val loading = HashSet<Int>()
        @Volatile var version = 0; private set
        private val listeners = ArrayList<() -> Unit>()
        @Synchronized fun onLoaded(l: () -> Unit) { listeners.add(l) }

        fun get(map: Int): T? {
            synchronized(this) {
                have[map]?.let { return it }
                val now = android.os.SystemClock.elapsedRealtime()
                missAt[map]?.let { if (now - it < MISS_RETRY_MS) return null }
                if (onMain()) {
                    if (loading.add(map)) exec.execute { fill(map) }
                    return null
                }
            }
            return fill(map)
        }

        private fun fill(map: Int): T? {
            val v = runCatching { load(map) }.getOrNull()
            val ls: List<() -> Unit>
            synchronized(this) {
                loading.remove(map)
                if (v != null) { have[map] = v; missAt.remove(map); version++ } else missAt[map] = android.os.SystemClock.elapsedRealtime()
                ls = if (v != null) ArrayList(listeners) else emptyList()
            }
            if (ls.isNotEmpty()) main.post { ls.forEach { it() } }
            return v
        }

        /** forget [map] (its file was rebuilt) */
        @Synchronized fun drop(map: Int) { have.remove(map); missAt.remove(map) }
    }
}
