package com.jrees.wowcompanion

/**
 * ONE CHARACTER'S DANGER ON ONE ZONE (SafePath's device plan, section 4; the user: "danger maps are going to change faction to
 * faction", Dalaran "faction specific as its a alliance city now"), shared by the device and the off-device harness
 * (tools/safepath-harness's RenderSafePath calls it), so the two weigh the mobs the same way by construction:
 *
 * 1. a town's faction-less people take its side ([QuestieNpcs.townSided]);
 * 2. the zone's own peoples friendly to all ([Rules.friendlyNames]) and WoW Forever's placeholder-level NPCs ([Rules.friendlyMinLevel])
 *    made the character's side's ([sided]);
 * 3. only the spawns hostile to the character's faction ([hostile]) into a [DangerGrid] for the character's level;
 * 4. the zone's typical danger taken off ([DangerGrid.typical]: an all-red zone steers round its worst camps only);
 * 5. a narrow stream waded ([WADE_DANGER]) and a building's rooms ([BUILDING_DANGER]) added where the lattice says so ([Danger.danger]).
 *
 * Pure: the spawns (on the zone's map, 0..1), the zone's rect, the faction, the level and the rules are the caller's (the harness: its
 * spec's values; the device: the per-uiMap rule data). [Cache] keeps the last grids by (map, faction, level, the spawns' version, the
 * avoid setting), so a level-up or a character switch builds again and nothing else does.
 */
object DangerInputs {
    /** a narrow stream waded (SafePath.NARROW_DEPTH) costs as the mobs' middling danger does: a drier way wins where there is one */
    const val WADE_DANGER = 2f
    /** a building's room footprint (the user: "No cutting through"): a route goes round a house unless its goal is in it */
    const val BUILDING_DANGER = 2f

    /**
     * A zone's own friend rules (typed, no game coordinates): spawns at or above [friendlyMinLevel] are placeholders (WoW Forever's own
     * NPCs at 9999; the specs use 100), and spawns whose name matches one of [friendlyNames] are a people the guides call friendly to
     * all (Zephras' Windshapers and High Order). Both are made the character's side's, unless already friendly to the other side.
     */
    class Rules(val friendlyMinLevel: Int = Int.MAX_VALUE, val friendlyNames: List<Regex> = emptyList()) {
        companion object { val NONE = Rules() }
    }

    /** the other faction ([TransportRoutes.ALLIANCE] <-> [TransportRoutes.HORDE]) */
    fun foeOf(mine: Int): Int = if (mine == TransportRoutes.HORDE) TransportRoutes.ALLIANCE else TransportRoutes.HORDE

    /** world yards (x north, y west) of a spawn at the map's ([u], [v]) on [rect] */
    fun world(rect: MapHarvest.Rect, u: Float, v: Float): DoubleArray {
        val ax = rect.l - rect.r; val ay = rect.t - rect.b
        return doubleArrayOf(rect.t - v * ay, rect.l - u * ax)
    }

    /**
     * Steps 1-2: [spawns] on the zone [rect] with each town's faction-less people on its side and [rules]' friends made the side of
     * [mine] (hostile to [foeOf] mine only). The towns, hubs and drafts read this list too: a friend made here is a town's NPC.
     */
    fun sided(spawns: List<QuestieNpcs.Spawn>, rect: MapHarvest.Rect, mine: Int, rules: Rules): List<QuestieNpcs.Spawn> {
        val ax = rect.l - rect.r; val ay = rect.t - rect.b
        val foe = foeOf(mine)
        return QuestieNpcs.townSided(spawns) { sp -> doubleArrayOf(rect.t - sp.y * ay, rect.l - sp.x * ax) }.map { sp ->
            // the placeholder level only for NPCs tagged for neither faction (the user, 2026-10-19: "Yes, other faction = danger")
            if ((sp.minLv >= rules.friendlyMinLevel && sp.hostile == 3 || rules.friendlyNames.any { it.containsMatchIn(sp.name) }) && sp.hostile != foe)
                QuestieNpcs.Spawn(sp.area, sp.x, sp.y, sp.minLv, sp.maxLv, sp.elite, foe, sp.name, sp.sub) else sp }
    }

    /** step 3's filter: the spawns hostile to [mine] (its own bit, or both); 0 = unknown, every hostile spawn */
    fun hostile(sided: List<QuestieNpcs.Spawn>, mine: Int): List<QuestieNpcs.Spawn> =
        if (mine == 0) sided else sided.filter { it.hostile == mine || it.hostile == 3 }

    /**
     * One character's danger on a zone: [grid] (the hostile spawns' weights, [DangerGrid.CAP] at most), [typical] (taken off every
     * cell), [k] the avoid setting's strength ([DangerGrid.strength]), [sided] (steps 1-2's spawns, for the towns) and [ms] the build.
     */
    class Danger(val grid: DangerGrid, val typical: Float, val k: Double, val rect: MapHarvest.Rect, val sided: List<QuestieNpcs.Spawn>,
                 val ms: Double) {
        private val ax = rect.l - rect.r; private val ay = rect.t - rect.b
        /** the mobs' danger at world ([x], [y]) less the zone's typical, never below 0 */
        fun mobs(x: Double, y: Double): Float = maxOf(0f, grid.at((rect.l - y) / ax, (rect.t - x) / ay) - typical)
        /** the extra costs that are not mobs: [wading] a narrow stream ([WADE_DANGER]), in a building's [rooms] ([BUILDING_DANGER]) */
        fun calm(wading: (Double, Double) -> Boolean, rooms: (Double, Double) -> Boolean): (Double, Double) -> Float =
            { x, y -> (if (wading(x, y)) WADE_DANGER else 0f) + (if (rooms(x, y)) BUILDING_DANGER else 0f) }
        /** the route's danger at world (x, y): [mobs] + [calm]'s (SafeRoute's danger) */
        fun danger(wading: (Double, Double) -> Boolean, rooms: (Double, Double) -> Boolean): (Double, Double) -> Float =
            { x, y -> mobs(x, y) + (if (wading(x, y)) WADE_DANGER else 0f) + (if (rooms(x, y)) BUILDING_DANGER else 0f) }
    }

    /**
     * Steps 3-4 on spawns already [sided] (step 1-2's list): the grid for a player of [level] and faction [mine] on [rect], the ground's
     * cost per cell from [ground] (world x, y; null = flat), the typical danger over the cells [inZone] (world x, y of each cell's
     * middle; null = none taken off), [avoid] the setting ("off" / "low" / "high").
     */
    fun grid(sided: List<QuestieNpcs.Spawn>, rect: MapHarvest.Rect, mine: Int, level: Int, avoid: String = "high",
             ground: ((Double, Double) -> Float)? = null, inZone: ((Double, Double) -> Boolean)? = null): Danger {
        val t0 = System.nanoTime()
        val ax = rect.l - rect.r; val ay = rect.t - rect.b
        val gr: ((Double, Double) -> Float)? = ground?.let { f -> { u, v -> val wx = rect.t - v * ay; val wy = rect.l - u * ax; f(wx, wy) } }
        val g = DangerGrid.build(hostile(sided, mine), mine, level, ax, ay, gr)
        val typical = if (inZone == null) 0f else g.typical { u, v -> inZone(rect.t - v * ay, rect.l - u * ax) }
        return Danger(g, typical, DangerGrid.strength(avoid), rect, sided, (System.nanoTime() - t0) / 1e6)
    }

    /** the whole of it: [spawns] (the zone's map's, 0..1) sided ([sided]) for [mine] by [rules], then [grid] */
    fun build(spawns: List<QuestieNpcs.Spawn>, rect: MapHarvest.Rect, mine: Int, level: Int, rules: Rules = Rules.NONE,
              avoid: String = "high", ground: ((Double, Double) -> Float)? = null, inZone: ((Double, Double) -> Boolean)? = null): Danger {
        val t0 = System.nanoTime()
        val d = grid(sided(spawns, rect, mine, rules), rect, mine, level, avoid, ground, inZone)
        return Danger(d.grid, d.typical, d.k, rect, d.sided, (System.nanoTime() - t0) / 1e6)
    }

    /** what a grid is built for: a new level, a character of the other side, new spawns (DangerStore.version) or setting, a new grid */
    data class Key(val map: Int, val faction: Int, val level: Int, val version: Int, val avoid: String)

    /** the last [max] grids (section 5 of the plan: 2 kept, under 2 MB each), the oldest dropped first; thread-safe */
    class Cache(private val max: Int = 2) {
        private val m = object : LinkedHashMap<Key, Danger>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Danger>?) = size > max
        }
        var hits = 0; private set
        var misses = 0; private set

        /** the grid for [key], built by [make] when it is not kept */
        @Synchronized fun get(key: Key, make: () -> Danger): Danger {
            m[key]?.let { hits++; return it }
            misses++
            return make().also { m[key] = it }
        }

        /** [build] for map [map] at the spawns' [version], kept */
        fun get(map: Int, version: Int, spawns: List<QuestieNpcs.Spawn>, rect: MapHarvest.Rect, mine: Int, level: Int,
                rules: Rules = Rules.NONE, avoid: String = "high", ground: ((Double, Double) -> Float)? = null,
                inZone: ((Double, Double) -> Boolean)? = null): Danger =
            get(Key(map, mine, level, version, avoid)) { build(spawns, rect, mine, level, rules, avoid, ground, inZone) }

        @Synchronized fun clear() = m.clear()
        @Synchronized fun size() = m.size
    }
}
