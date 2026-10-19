package com.jrees.wowcompanion

import kotlin.math.hypot

/**
 * THE ROUTE SEQUENCE ONE ZONE'S ROUTE IS PLANNED BY, shared by the device and the off-device harness (tools/safepath-harness's
 * RenderSafePath calls it), so the two plan the same way by construction:
 *
 * 1. the network's way with the ground's legs ([SafePath.routeOn]: the walk on and off, the network's pieces walked straight only where
 *    [Climb.walkable], the direct rival, the walk unlooped);
 * 2. a wade longer than [SafePath.NARROW_YD] on end (the user's narrow-stream ruling) routed again with no wading at all;
 * 3. the way round the danger capped against the way ignoring it ([SafePath.capDetour]: the user, "Mobs are a cost, not a wall").
 *
 * Pure: the lattice [g], the danger ([danger]; [calm] the same with no mobs: a wade's and a building's cost only, [k] its weight) and the
 * water ([deep] a wall, [waterAt] the water grid's depth, [deckAt] a walkway's deck over it, [rawDeep] the liquid's own surface deeper
 * than the ford at a spot) are the caller's.
 */
class SafeRoute(private val g: Climb.Grid, private val danger: (Double, Double) -> Float, private val calm: (Double, Double) -> Float,
                private val k: Double, private val deep: (Double, Double) -> Boolean, private val waterAt: (Double, Double) -> Float,
                private val deckAt: (Double, Double) -> Boolean, private val rawDeep: ((Double, Double) -> Boolean)?,
                private val limits: Climb.Limits? = null) {

    /** a planned way: its points (x, y, and [Climb.IN_CAVE] / [Climb.LIFT] / [Climb.SWIM] marks), and whether it reached its goal */
    class Result(val pts: List<DoubleArray>, val reached: Boolean) {
        val yd: Double get() = length(pts)
    }

    /** deeper than the ford at world ([x], [y]) off a deck: by the water grid's vertices or by the liquid's surface over the ground at
     *  the spot itself ([rawDeep], as py/wetcheck.py reads it: the grid keeps tenths, and Loch Modan's route C walked 13 yd along a
     *  stream 1.51-1.56 deep the grid read 1.5) */
    fun overFord(x: Double, y: Double): Boolean {
        val d = waterAt(x, y)
        return !deckAt(x, y) && ((rawDeep != null && rawDeep(x, y)) || (!d.isNaN() && d != SafePath.Water.BRIDGE && d > SafePath.FORD_DEPTH))
    }
    /** no wading at all: every spot deeper than the ford a wall (the strict retry's water) */
    val strictDeep: (Double, Double) -> Boolean = { x, y -> overFord(x, y) }

    /** the longest stretch of [w] deeper than the ford on end (sampled each half yard; a cave's floor dry) */
    fun longestWade(w: List<DoubleArray>): Double {
        var run = 0.0; var best = 0.0
        for ((pa, pb) in w.zipWithNext()) {
            if (Climb.inCave(pa) || Climb.inCave(pb)) { run = 0.0; continue }
            val n = maxOf(1, (hypot(pb[0] - pa[0], pb[1] - pa[1]) * 2).toInt())
            for (q in 0 until n) {
                val x = pa[0] + (pb[0] - pa[0]) * q / n; val y = pa[1] + (pb[1] - pa[1]) * q / n
                if (overFord(x, y)) { run += 0.5; best = maxOf(best, run) } else run = 0.0
            }
        }
        return best
    }

    /**
     * the way from [from] to [to] on network [net] ([clear]: a network line's own check, SafePath.clearOf), the ground's links
     * [links] (caves, lifts, a declared swim for a goal on its island) taken. [label] names it in [log]'s lines (WADE, DETOUR).
     *
     * Whether it reached its goal is the word of the ground leg the way ends with (SafePath.routeOn tries more legs than it keeps - the
     * walk off before the turn, the direct rival - so the last leg asked is not always the one the way ends with); the strict retry's
     * way's when it was taken.
     */
    fun plan(net: SafePath.Net, from: DoubleArray, to: DoubleArray, clear: (DoubleArray, DoubleArray) -> Boolean, links: List<Climb.Link>,
             label: String = "", toExit: Boolean = false, log: (String) -> Unit = {}): Result {
        var lastReached = true
        // each leg's end and whether it reached its goal
        val legEnds = ArrayList<Pair<DoubleArray, Boolean>>()
        fun reachedBy(way: List<DoubleArray>?): Boolean = way?.lastOrNull()?.let { e -> legEnds.lastOrNull { (p, _) -> p[0] == e[0] && p[1] == e[1] }?.second } ?: lastReached
        fun legBy(water: (Double, Double) -> Boolean): (DoubleArray, DoubleArray) -> List<DoubleArray>? = { a, b ->
            Climb.route(g, a, b, danger, k, water, links, limits)?.also { lastReached = it.reached; it.pts.lastOrNull()?.let { e -> legEnds.add(e to it.reached) } }?.pts }
        val leg = legBy(deep)
        val way0 = SafePath.routeOn(net, from[0], from[1], to[0], to[1], leg, toExit = toExit, clear = clear, walkable = { a, b -> Climb.walkable(g, a, b, deep) })
            ?: leg(from, to) ?: listOf(from, to)
        var reached = reachedBy(way0)
        // the narrow-stream ruling's other half: no wade longer than NARROW_YD on end. A route that wades longer (along a stream, Felwood:
        // 2.3 yd for 12 yd) is routed again with no wading at all
        val wayW = if (longestWade(way0) <= SafePath.NARROW_YD) way0 else {
            log("WADE route $label: a wade ${longestWade(way0)} yd on end - routed again with no wading")
            val strictLeg = legBy(strictDeep)
            (SafePath.routeOn(net, from[0], from[1], to[0], to[1], strictLeg, toExit = toExit, clear = clear, walkable = { a, b -> Climb.walkable(g, a, b, strictDeep) })
                ?: strictLeg(from, to))?.also { reached = reachedBy(it) } ?: way0
        }
        // the detour cap: the way round the danger against the way with no danger counted (only a reached way; the rival must reach the
        // goal itself and wade no longer)
        val way = if (!reached) wayW else {
            val direct = Climb.route(g, from, to, calm, k, deep, links, limits)?.pts?.takeIf { d -> d.size >= 2 && hypot(d.last()[0] - to[0], d.last()[1] - to[1]) < 1.0 }
            SafePath.capDetour(wayW, direct?.takeIf { longestWade(it) <= SafePath.NARROW_YD }).also {
                if (it !== wayW) log("DETOUR route $label: round the danger ${length(wayW).toInt()} yd, capped: the way ignoring it ${length(it).toInt()} yd") }
        }
        return Result(way, reached)
    }

    companion object {
        fun length(w: List<DoubleArray>) = w.zipWithNext { a, b -> hypot(b[0] - a[0], b[1] - a[1]) }.sum()
    }
}
