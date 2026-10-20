package com.jrees.wowcompanion

/**
 * THE GHOST'S CORPSE RUN (the user, 2026-10-20: "when im dead I want the absolute quickest path back to my body to show"). While the
 * player is a ghost and the map document carries the corpse on this map, MapView plans a leg from the player to the corpse with the
 * focused quest's machinery (the shipped network, else SafePath's draft, else the danger grid's ground), keyed [KEY], with these
 * planner settings - pure, so the tests and MapView share them:
 *  - no danger at all ([K] = 0, no character danger, no live aggro): a ghost cannot be attacked, so the shortest walk wins;
 *  - no detour cap (with no danger there is no "way round the danger" to cap);
 *  - no transports ([walkable]: no portal, updraft, glide or ride - a ghost cannot take them; stairs, decks, doors and swims are walked);
 *  - the ground's walls (cliffs, buildings, deep water) still hold.
 * A corpse off this map (or on another continent) keeps the straight arrow. [Run] holds the planned leg and drops it the moment the
 * player is alive again.
 */
object CorpseRoute {
    /** the corpse leg's key in MapView's per-quest leg stores (quest ids are positive) */
    const val KEY = -1
    /** the danger's weight on the corpse leg: none */
    const val K = 0.0
    /** the links a ghost walks (anything else - a portal, an updraft, a glide, a boat, a tram - is a transport it cannot use) */
    val WALK_LINKS = setOf("stairs", "stair", "deck", "door", "swim")

    /** may a ghost take link [l]: only a walked one, both ways (a one-way link is a portal's or an updraft's) */
    fun walkable(l: ShippedNet.Link): Boolean = !l.oneWay && l.kind.lowercase() in WALK_LINKS

    /** the corpse leg on the shipped network: [ShippedRoute.plan] with no danger, no live aggro, no cap and only the walked links */
    fun plan(sec: ShippedNet.Section, ground: (Double, Double) -> Float, from: DoubleArray, to: DoubleArray,
             roadmap: ShippedRoute.Roadmap? = ShippedRoute.roadmapOf(sec), attacher: ShippedRoute.Attacher = ShippedSnap.attacher(),
             stitchGround: ShippedSnap.Ground? = null): ShippedRoute.Result =
        ShippedRoute.plan(sec, ground, null, from, to, k = K, roadmap = roadmap, attacher = attacher, stitchGround = stitchGround,
            live = null, links = ::walkable)

    /** the corpse leg on the danger grid (no shipped network, no draft): the ground's walls only, never its danger */
    fun gridLeg(g: DangerGrid, from: DoubleArray, to: DoubleArray): List<DoubleArray> = g.route(from, to, K, groundOnly = true)

    /** the corpse leg is wanted: a ghost with its corpse on this map (the map's 0..1) */
    fun wanted(ghost: Boolean, x: Double, y: Double): Boolean = ghost && x in 0.0..1.0 && y in 0.0..1.0

    /** the WCMAP line for a plan */
    fun said(mapId: Int, yd: Double, how: String? = null): String =
        "safepath ghost: corpse on map $mapId: ${yd.toInt()} yd (danger off${how?.let { "; $it" } ?: ""})"

    /**
     * ONE GHOST'S RUN: [see] each map document (ghost or not, the corpse), [leg] the leg shown (the map's 0..1, the player first, the
     * corpse last; null = straight to the corpse). [see] answers true when the leg must be dropped (alive again, or the corpse gone
     * off this map): the caller then clears its stores under [KEY].
     */
    class Run {
        var ghost = false; private set
        var leg: List<DoubleArray>? = null
            set(v) { field = if (ghost) v?.takeIf { it.size >= 2 } else null }

        private var on = false

        fun see(ghost: Boolean, x: Double, y: Double): Boolean {
            val was = on || leg != null
            this.ghost = ghost
            on = CorpseRoute.wanted(ghost, x, y)
            if (!on) leg = null
            return was && !on
        }

        /** the corpse leg's NEXT CHECKPOINT (leg[1]) while a ghost with a leg, else the corpse itself (straight): where the red rim
         *  arrow points and where the game's pin / compass / world marker go ([MapView.routePin]) */
        fun next(corpseX: Double, corpseY: Double): DoubleArray =
            leg?.takeIf { ghost && it.size >= 2 }?.get(1) ?: doubleArrayOf(corpseX, corpseY)
        /** the leg bends (a checkpoint before the corpse) */
        val bent: Boolean get() = ghost && (leg?.size ?: 0) > 2
    }
}
