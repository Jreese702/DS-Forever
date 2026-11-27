package com.jrees.wowcompanion

/**
 * THE MAP'S PIN KIND, WORKED OUT ONCE PER KIND OF NPC (issue 612: the start screen ran out of Java heap). The kind depends only on
 * the NPC's flags and title, so it is kept under those. It was kept under the Poi OBJECT: every position update builds new Poi
 * objects (zoneRows / qdbRows), so the old identity map grew with every update and kept every one of them alive; a replayed log
 * filled the 512 MB heap in a minute or two. Bounded by the distinct (flags, title) pairs, and past [MAX] of those it starts again.
 * Not thread-safe (the map's UI thread only). Pure.
 */
class PoiKindMemo(private val kindOf: (flags: Int, title: String) -> Int) {
    private val byFlags = HashMap<Int, HashMap<String, Int>>()
    /** the kinds kept */
    var size = 0
        private set

    fun of(p: PoiStore.Poi): Int = of(p.flags, p.title)

    fun of(flags: Int, title: String): Int {
        byFlags[flags]?.get(title)?.let { return it }
        if (size >= MAX) { byFlags.clear(); size = 0 }
        val k = kindOf(flags, title)
        byFlags.getOrPut(flags) { HashMap() }[title] = k
        size++
        return k
    }

    companion object {
        /** far more kinds than any zone's NPCs have (a vendor's or a trainer's title, a few flag sets) */
        const val MAX = 4096
    }
}
