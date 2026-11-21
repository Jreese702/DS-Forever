package com.jrees.wowcompanion

/**
 * TEST BUILD 1489'S PICTURE CUTS, the pure part (the user's picks 2026-11-18, #56; off-device tests: LowMemCutsTest). Scene (the
 * loading pictures) and UiKit (the kit) hold Android objects from their first touch, so their rules live here.
 */
object LowMemCuts {
    // ------------------------------------------------------------------ the loading pictures (Scene)

    /** at most this many loading pictures stay decoded (the reveal draws three passes at once) */
    const val SCENE_MAX_HELD = 4
    /** the bottom screen's size: no loading picture is decoded larger than needs to cover it */
    const val SCENE_TARGET_W = 1240
    const val SCENE_TARGET_H = 1080

    /** the decoder's power-of-two sample for a [w] x [h] picture that must still cover [tw] x [th] */
    fun sampleFor(w: Int, h: Int, tw: Int = SCENE_TARGET_W, th: Int = SCENE_TARGET_H): Int {
        var s = 1
        while (w / (s * 2) >= tw && h / (s * 2) >= th) s *= 2
        return s
    }

    /** the held pictures beyond [cap], least recently asked first ([lru] in that order) */
    fun <K> overCap(lru: Collection<K>, cap: Int = SCENE_MAX_HELD): List<K> = lru.take((lru.size - cap).coerceAtLeast(0))

    // ------------------------------------------------------------------ LIGHT 8 (LIGHT8_PLAN.md; DeviceTier.light8, read live)

    /** Light 8: at most this many loading pictures decoded (the reveal draws three passes at once) */
    const val SCENE_MAX_HELD_8 = 3
    /** the loading pictures held at most: [SCENE_MAX_HELD_8] on Light 8, [SCENE_MAX_HELD] otherwise (test build 1516's) */
    fun sceneMaxHeld(light8: Boolean): Int = if (light8) SCENE_MAX_HELD_8 else SCENE_MAX_HELD
    /** Light 8: a loading picture Android decoded as ARGB_8888 (it has alpha) is copied down to RGB_565 (the scene is drawn opaque).
     *  A guard: the kit's loading pictures carry no alpha and already decode as RGB_565, so Light 8 saves one picture (~2.7 MB) here */
    fun sceneForce565(light8: Boolean): Boolean = light8

    /** Light 8: the per-zone data (terrain, roads, SafePath zones / water / drafts / nets) keeps at most this many zones */
    const val ZONE_CAP_8 = 8
    /** the per-zone caches' bound: 0 (no bound, test build 1516's) off Light 8; on it [ZONE_CAP_8], never below the keep set + 1 */
    fun zoneBound(light8: Boolean, keepSize: Int): Int = if (!light8) 0 else maxOf(ZONE_CAP_8, keepSize + 1)

    /** the climb maps' bound: 3 on every Thor (test build 1516's) */
    const val CLIMB_MAX = 3
    /** the climb maps kept outside the keep set: 1 on Light 8 (the keep set and one recent zone), no such rule (-1) otherwise */
    fun climbOthers(light8: Boolean): Int = if (light8) 1 else -1

    /** the player's zone first, then its neighbours, no 0, no repeat ([player] 0 = not known: none). Pure */
    fun zoneKeep(player: Int, neighbours: Collection<Int>): List<Int> =
        if (player == 0) emptyList() else (listOf(player) + neighbours).filter { it != 0 }.distinct()

    /** the zone the player is in now: the position's map (SafePath's walk), else the zone the map asked for last (0 = not known) */
    fun playerZone(): Int = runCatching { SafePath.lastMap }.getOrDefault(0).takeIf { it != 0 } ?: runCatching { ShippedNets.lastMap }.getOrDefault(0)

    /** the zones the per-zone caches keep on Light 8: the player's and the maps its exits lead into (ShippedNets' network) */
    fun zoneKeepNow(): List<Int> {
        val p = playerZone()
        if (p == 0) return emptyList()
        return zoneKeep(p, runCatching { ShippedNets.files.peek(p)?.neighbours }.getOrNull() ?: emptySet<Int>())
    }

    /** a memory warning (running-low or worse, MemWatch's reliefs too) lets the per-zone caches go down to the keep set */
    fun zoneTrims(level: Int): Boolean = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW

    /**
     * ONE PER-ZONE CACHE (Terrain, Roads, SafePath): test build 1516's unbounded RouteWork.Files off Light 8 (no keep set, no bound);
     * on Light 8 at most [zoneBound] zones, the least recently used not in the keep set going first and the player's zone last.
     * [light] and [keepNow] are read at every put ([light] is DeviceTier.light8, live). [holdJob]: the load-time hold (LoadHold) for
     * zones other than the player's.
     */
    fun <T : Any> zoneFiles(sizeOf: (T) -> Long, holdJob: String? = null, light: () -> Boolean = { DeviceTier.light8() },
                            keepNow: () -> List<Int> = { zoneKeepNow() }, load: (Int) -> T?): RouteWork.Files<T> =
        RouteWork.Files(keep = { if (light()) keepNow() else emptyList() }, sizeOf = sizeOf,
            bound = { if (light()) zoneBound(true, keepNow().size) else 0 }, playerFirst = { light() },
            holdJob = holdJob, holdIf = { m -> LoadHold.otherZone(m) }, quietReload = { light() }, load = load)

    // ------------------------------------------------------------------ the kit (UiKit)

    /** the kit's pictures while the game runs, bytes */
    const val KIT_GAME_CAP = 24L * 1024 * 1024
    /** a picture drawn within this long always stays (no re-reading per frame) */
    const val KIT_IDLE_MS = 30_000L

    /** the names to let go so [sizes] (bytes) is under [cap]: idle ones only (not drawn since [idleMs]), the oldest first */
    fun kitOverCap(sizes: Map<String, Long>, used: Map<String, Long>, nowMs: Long, cap: Long = KIT_GAME_CAP,
                   idleMs: Long = KIT_IDLE_MS): List<String> {
        var total = sizes.values.sum()
        if (total <= cap) return emptyList()
        val out = ArrayList<String>()
        for (n in sizes.keys.filter { nowMs - (used[it] ?: 0L) >= idleMs }.sortedBy { used[it] ?: 0L }) {
            if (total <= cap) break
            out.add(n); total -= sizes.getValue(n)
        }
        return out
    }

    // ------------------------------------------------------------------ memory work #56 (D2): what Android's pressure leaves

    /** the indoor plans kept at [level] while the map is [shown] (2) or not (0); null = none go (below running-low) */
    fun plansKept(level: Int, shown: Boolean): Int? =
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) null
        else if (shown && !ShippedNets.severe(level)) 2 else 0

    /**
     * THE ICON CACHES' SIZE BY THE THOR (memory work #56, D2: CastView's decoded icons were never let go, 3 - 10 MB and growing; Icons'
     * own LRU held 600): how many 64 px icons (~16 KB each) stay decoded - 160 on the 8 GB Thors (Base, Lite), 240 on the 12 GB Pro,
     * 400 on the 16 GB Max and an unknown RAM (a full bag, both action bars and the spell book's page fit in the smallest)
     */
    fun iconsKept(totalMb: Long, lite: Boolean = false): Int = when {
        DeviceTier.eightGb(totalMb, lite) -> 160
        DeviceTier.lowMemory(totalMb) -> 240
        else -> 400
    }

    /** the device's icon budget now ([iconsKept] for this Thor; DeviceTier read when it is known) */
    fun iconsKeptNow(): Int = iconsKept(DeviceTier.totalMb, runCatching { DeviceTier.isLite() }.getOrDefault(false))

    /** a map keeping at most [max]() entries (asked at each put: the device's budget once it is known), the least recently used going first */
    class SizedLru<K, V>(private val max: () -> Int) : LinkedHashMap<K, V>(16, 0.75f, true) {
        // (a smaller budget than the map holds: every put brings it down to the budget, not one entry at a time)
        override fun put(key: K, value: V): V? {
            val was = super.put(key, value)
            val m = max().coerceAtLeast(1)
            while (size > m) remove(keys.first())
            return was
        }
    }

    /** a side cache's names whose picture is no longer loaded ([loaded]): they go with it (UiKit's tiles and light measures). Pure */
    fun sideGone(side: Collection<String>, loaded: Collection<String>): List<String> { val l = loaded.toSet(); return side.filter { it !in l } }

    /** MinimapTiles' tiles kept at [level] (full, small): null = none go (below running-low); at running-low the newest [MINIMAP_FULL] /
     *  [MINIMAP_SMALL] (about what one screen draws: a tile on screen is decoded again), at running-critical, onLowMemory or a
     *  background level none (memory work #56, D2: the tiles were never trimmed, 9 - 14 MB in the reports) */
    fun minimapKept(level: Int): Pair<Int, Int>? = when {
        level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> null
        ShippedNets.severe(level) -> 0 to 0
        else -> MINIMAP_FULL to MINIMAP_SMALL
    }
    const val MINIMAP_FULL = 6
    const val MINIMAP_SMALL = 64
}
