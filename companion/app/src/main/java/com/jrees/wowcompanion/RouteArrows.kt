package com.jrees.wowcompanion

/**
 * THE ROUTE'S ARROWS (feat/route-arrows; the user, 2026-11-17: "on the mini map every 5 dots I want a blue arrow so that its
 * consistent"). Home's map draws every route (Polestar's path, SafePath's stretches, the corpse run, the cursor's line, QuestTrail's
 * path) as gold dots [SPACING] px apart marching [SPEED] px a second toward the goal; now every [EVERY]th dot is the compass's light
 * blue chevron (Trail.lua cp43's conveyor arrow) pointing along the route at that point, moving with the dots. Pure: the dots' dash
 * (four dots, then the arrow's slot left empty) and where the arrows stand along a path, from one clock.
 */
object RouteArrows {
    /** px between two dots along the route */
    const val SPACING = 16f
    /** every EVERY-th dot is an arrow */
    const val EVERY = 5
    /** px a second the dots (and arrows) move toward the goal */
    const val SPEED = 24f
    /** one dash cycle: EVERY slots */
    const val PERIOD = SPACING * EVERY
    /** a dot's dash length (the round cap makes it a dot) */
    const val DOT = 0.1f

    /** the dots' dash: EVERY - 1 dots SPACING apart, then a gap where the EVERY-th dot (the arrow) stands */
    val DASH: FloatArray = FloatArray(2 * (EVERY - 1)) { i -> if (i % 2 == 0) DOT else SPACING - DOT }
        .also { it[it.size - 1] = 2 * SPACING - DOT }

    /** how far the pattern has moved along the route at [tMs] (0 until PERIOD; worked in double: a long clock stays exact) */
    fun travel(tMs: Long): Float = ((tMs.toDouble() / 1000.0 * SPEED) % PERIOD).toFloat()

    /** the dash's phase at [tMs] (a dash path's phase runs against the path, hence minus) */
    fun phase(tMs: Long): Float = -travel(tMs)

    /** the dots' distances along a path [len] px long at [tMs] (what [DASH] at [phase] draws; for the checks) */
    fun dotDistances(len: Float, tMs: Long): FloatArray {
        val v = travel(tMs)
        val out = ArrayList<Float>()
        var base = -PERIOD
        while (base <= len) {
            for (k in 0 until EVERY - 1) { val s = base + k * SPACING + v; if (s in 0f..len) out.add(s) }
            base += PERIOD
        }
        return out.sorted().toFloatArray()
    }

    /** the arrows' distances along a path [len] px long at [tMs]: the empty slot of every cycle */
    fun arrowDistances(len: Float, tMs: Long): FloatArray {
        val first = ((EVERY - 1) * SPACING + travel(tMs)) % PERIOD
        if (len < first) return FloatArray(0)
        val n = ((len - first) / PERIOD).toInt() + 1
        return FloatArray(n) { first + it * PERIOD }
    }

    /** the chevron's shape in px, forward along the route: the apex ahead, the two ends behind and to each side (Trail.lua C.PA.ARM) */
    const val AHEAD = 4.5f
    const val BEHIND = 3.5f
    const val HALF_W = 7f
    const val W = 4f
    const val RIM = 7f
}
