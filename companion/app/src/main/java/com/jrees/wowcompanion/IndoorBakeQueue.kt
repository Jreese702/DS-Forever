package com.jrees.wowcompanion

import java.io.File

/** One bounded building per call; the Android worker ([IndoorScheduler]) supplies position and yield policy. */
internal class IndoorBakeQueue(
    private val store: IndoorBakeStore,
    private val build: String,
    private val catalogue: List<IndoorBakeStore.Work>,
    /** buildings that failed before with the same input identity (IndoorDeferrals): skipped, still counted as deferred */
    private val skip: Set<String> = emptySet()
) {
    data class Position(val continent: Int, val x: Double, val y: Double) {
        init { require(continent >= 0 && x.isFinite() && y.isFinite()) }
    }
    private val attempted = hashSetOf<String>()
    private val finished = hashSetOf<String>()

    init {
        require(catalogue.map { it.key }.distinct().size == catalogue.size)
        for (work in catalogue) if (store.completed(work) != null) finished.add(work.signature)
    }

    val total: Int get() = catalogue.size
    /** buildings with verified output (found at start or baked by this instance) */
    val ready: Int get() = finished.size
    /** buildings that failed in this instance, plus those deferred from before (until their input identity changes) */
    val deferred: Int get() = attempted.count { it !in finished } + skip.count { it !in finished && it !in attempted }

    /**
     * Nearest first. Footprints nest (a tavern inside its district WMO), so among buildings the player is equally near -
     * typically inside several - the smallest footprint wins: that is the building they are in (MapView's placeHere rule).
     */
    fun next(position: Position?): IndoorBakeStore.Work? {
        val open = catalogue.asSequence().filter { it.signature !in finished && it.signature !in attempted && it.signature !in skip }
        // NO POSITION EVER (a fresh install's first session: character select / launcher; T3 run 2 baked the big dungeons first,
        // ~6.7 of the first 10.5 min, by key order): open-world buildings before instance-only maps, the smallest footprint first
        // (inns, houses, shops before keeps and cathedrals), the key last. With a position: nearest-first as before
        if (position == null) return open.minWithOrNull(
            compareBy<IndoorBakeStore.Work> { if (openWorld(it)) 0 else 1 }.thenBy { smallest(it) }.thenBy { it.key })
        return open.minWithOrNull(compareBy<IndoorBakeStore.Work> { distance(it, position) }.thenBy { area(it, position) }.thenBy { it.key })
    }

    /** continents that hold many buildings are the open world; an instance map has only a few roots of its own */
    private val worldContinents: Set<Int> by lazy {
        val perContinent = HashMap<Int, Int>()
        for (w in catalogue) for (c in w.locations.mapTo(HashSet()) { it.continent }) perContinent[c] = (perContinent[c] ?: 0) + 1
        val enough = maxOf(OPEN_WORLD_MIN, catalogue.size * OPEN_WORLD_SHARE / 100)
        perContinent.filterValues { it >= enough }.keys
    }

    private fun openWorld(work: IndoorBakeStore.Work): Boolean = work.locations.any { it.continent in worldContinents }

    private fun smallest(work: IndoorBakeStore.Work): Double = work.locations.minOfOrNull { (it.xmax - it.xmin) * (it.ymax - it.ymin) } ?: Double.MAX_VALUE

    companion object {
        /** a continent with at least this many buildings (and [OPEN_WORLD_SHARE] % of the catalogue) is open world */
        const val OPEN_WORLD_MIN = 10
        const val OPEN_WORLD_SHARE = 5
    }

    /** [publish] = false leaves the publication to the caller (the scheduler batches it: every publish rehashes the catalogue) */
    fun runOne(position: Position?, publish: Boolean = true, produce: (IndoorBakeStore.Work, File) -> Unit): IndoorBakeStore.Work? {
        val work = next(position) ?: return null
        attempted.add(work.signature)
        store.bake(work) { produce(work, it) }
        // Even a failed publication leaves its completed building available for the next run.
        if (publish) store.publish(build, catalogue)
        finished.add(work.signature)
        return work
    }

    fun publishCompleted(): Int = store.publish(build, catalogue)

    /** squared yards from [position] to [work]'s nearest footprint on the same continent (0 inside; infinite elsewhere) */
    fun distance(work: IndoorBakeStore.Work, position: Position?): Double {
        if (position == null) return 0.0
        return work.locations.asSequence().filter { it.continent == position.continent }.map {
            val dx = (it.xmin - position.x).coerceAtLeast(0.0) + (position.x - it.xmax).coerceAtLeast(0.0)
            val dy = (it.ymin - position.y).coerceAtLeast(0.0) + (position.y - it.ymax).coerceAtLeast(0.0)
            dx * dx + dy * dy
        }.minOrNull() ?: Double.POSITIVE_INFINITY
    }

    private fun area(work: IndoorBakeStore.Work, position: Position?): Double {
        if (position == null) return 0.0
        return work.locations.asSequence().filter { it.continent == position.continent }
            .map { (it.xmax - it.xmin) * (it.ymax - it.ymin) }.minOrNull() ?: Double.POSITIVE_INFINITY
    }
}
