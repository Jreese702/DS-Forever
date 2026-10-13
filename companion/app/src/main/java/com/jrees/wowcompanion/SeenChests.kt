package com.jrees.wowcompanion

/**
 * THE CHESTS ACTUALLY SEEN (b-ghost-chests; the "only what's actually seen" rule SeenNpcs keeps for NPCs). QuestieDB's chest rows
 * are SPAWN SPOTS: a zone has many and only a few hold a chest at a time, so drawing them all showed chests that were not there.
 * A chest draws only where the minimap probe (Map.lua mp1, Find Treasure on) named one. Kept in memory for this app life, per map,
 * in the map's 0..1: a sighting is GONE when a later scan that could see it (Find Treasure on, the spot well inside the minimap's
 * radius) does not name it (looted, or it was a moving dot), and after KEEP_MS whatever happens (a chest out of range cannot be
 * checked). Not a file: a chest is a moment, not a world fact. Both maps draw from here (MapView feeds it, the Map room reads it).
 */
object SeenChests {
    class Chest(val map: Int, var nx: Double, var ny: Double, var at: Long)

    const val KEEP_MS = 10 * 60_000L
    const val SAME_YD = 8.0          // a sighting this close to a kept one is that chest (the probe's dots wander a few yards)
    const val CHECK_EDGE = 0.8       // of the minimap's radius: inside it a kept chest the scan did not name is gone

    /** the game's chest names (Quests.lua QDB_NODE_NAMES.chest): a probed dot of one is a chest even on a map QuestieDB lacks it */
    val NAMES = setOf("Battered Chest", "Tattered Chest", "Solid Chest", "Large Battered Chest", "Large Iron Bound Chest",
        "Large Solid Chest", "Large Mithril Bound Chest", "Mithril Bound Chest", "Battered Footlocker", "Waterlogged Footlocker",
        "Dented Footlocker", "Mossy Footlocker", "Scarlet Footlocker")

    private val list = ArrayList<Chest>()

    /**
     * one probe scan of [map]: [seen] the chest dots it named (nx, ny), the player at [px], [py], [ax] / [ay] the yards across / down
     * the map's 0..1, [radiusYd] the minimap's view radius, [canSee] whether the scan could see chests at all (Find Treasure on).
     * True when what draws changed.
     */
    fun probe(map: Int, seen: List<DoubleArray>, px: Double, py: Double, ax: Double, ay: Double, radiusYd: Double?,
              canSee: Boolean, now: Long): Boolean {
        var changed = expire(now)
        fun yd(c: Chest, x: Double, y: Double) = Math.hypot((c.nx - x) * ax, (c.ny - y) * ay)
        val matched = HashSet<Chest>()
        for (s in seen) {
            val near = list.filter { it.map == map && it !in matched }.minByOrNull { yd(it, s[0], s[1]) }
            if (near != null && yd(near, s[0], s[1]) <= SAME_YD) {
                near.nx = s[0]; near.ny = s[1]; near.at = now; matched.add(near)
            } else {
                val c = Chest(map, s[0], s[1], now); list.add(c); matched.add(c); changed = true
            }
        }
        if (canSee && radiusYd != null && radiusYd > 0) {
            if (list.removeAll { it.map == map && it !in matched && yd(it, px, py) <= radiusYd * CHECK_EDGE }) changed = true
        }
        return changed
    }

    /** the chests to draw on [map] now */
    fun on(map: Int, now: Long): List<Chest> { expire(now); return list.filter { it.map == map } }

    private fun expire(now: Long): Boolean = list.removeAll { now - it.at > KEEP_MS }

    fun clear() = list.clear()
}
