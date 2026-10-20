package com.jrees.wowcompanion

/**
 * THE GHOST'S CORPSE RUN (the user, 2026-10-20: "when im dead I want the absolute quickest path back to my body to show"). While the
 * player is a ghost and the map document carries the corpse on this map, MapView plans a leg from the player to the corpse with the
 * focused quest's machinery (the shipped network, else SafePath's draft, else the danger grid's ground), keyed [KEY], with these
 * planner settings - pure, so the tests and MapView share them:
 *  - no danger at all ([K] = 0, no character danger, no live aggro): a ghost cannot be attacked, so the shortest walk wins;
 *  - no detour cap (with no danger there is no "way round the danger" to cap);
 *  - no transports ([walkable]: no portal, updraft, glide or ride - a ghost cannot take them; stairs, decks, doors and swims are walked);
 *  - WALK ON WATER (the user's ruling): water of any depth is walked on its surface; lava stays a wall;
 *  - DROPS (the user: "Yes, add drops"): a ghost takes no fall damage, so a cliff may be dropped DOWN (never climbed) where the
 *    zone's heights say the far side is lower by more than a step (the grid's leg: DangerGrid's ghost search) - the shipped network
 *    has no drop edges, so the grid's leg is followed instead when it is clearly shorter ([pick]);
 *  - AVOID marks not paid (they mark danger the ghost need not fear);
 *  - the ground's other walls (cliffs upward, buildings, lava) still hold.
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

    /** the corpse leg on the shipped network: [ShippedRoute.plan] with no danger, no live aggro, no cap, only the walked links, no
     *  AVOID or wade surcharge, swims whatever the goal; [fine] the fine ground (ShippedSnap's) read as a ghost's ([ShippedSnap.Ground.ghost]:
     *  water walked, lava a wall) for the attach snaps, the router's walks and the shipped-line checks; without it, [ground] */
    fun plan(sec: ShippedNet.Section, ground: (Double, Double) -> Float, from: DoubleArray, to: DoubleArray,
             roadmap: ShippedRoute.Roadmap? = ShippedRoute.roadmapOf(sec), fine: ShippedSnap.Ground? = null,
             log: ((String) -> Unit)? = null): ShippedRoute.Result {
        val g = fine?.ghost()
        return ShippedRoute.plan(sec, g?.asCost ?: ground, null, from, to, k = K, roadmap = roadmap, attacher = ShippedSnap.attacher(g, log),
            stitchGround = g, live = null, links = ::walkable, ghost = true)
    }

    /** which leg a ghost follows */
    enum class Src { NET, GRID }
    /**
     * the network's leg ([netYd]: the shipped network or the draft, null none) or the grid's ([gridYd]: drops, water, null none): the
     * NETWORK'S whenever there is one. The device's grid knows the ground only as 25 yd cells (their mean heights and slopes), so its
     * shortcut over a river or down a drop cannot see a bank the ghost cannot climb (test28, Zephras: "the safepath is making me walk down
     * this river over to a bridge, except I am under it and cant go up") - only the shipped network's ways are walls-checked. The grid's
     * leg is for a zone with no network. ([was] kept for the callers; the choice no longer flips.)
     */
    @Suppress("UNUSED_PARAMETER")
    fun pick(netYd: Double?, gridYd: Double?, was: Src?): Src? = when {
        netYd != null -> Src.NET
        gridYd != null -> Src.GRID
        else -> null
    }

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
