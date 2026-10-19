package com.jrees.wowcompanion

/**
 * THE ROUTE SEQUENCE ONE ZONE'S ROUTE IS PLANNED BY (moved verbatim out of the harness's RenderSafePath.levels2, so the device and the
 * harness plan the same way): the network's way with the ground's legs (SafePath.routeOn, its walk unlooped), a wade longer than
 * SafePath.NARROW_YD routed again with no wading, then the way round the danger capped against the way ignoring it (SafePath.capDetour).
 * Pure: the lattice [cg], the danger and the water are the caller's.
 */
class SafeRoute(private val cg: Climb.Grid, private val danger: (Double, Double) -> Float, private val calm: (Double, Double) -> Float,
                private val k: Double, private val deep: (Double, Double) -> Boolean, private val waterAt: (Double, Double) -> Float,
                private val deckAt: (Double, Double) -> Boolean, private val rawDeep: ((Double, Double) -> Boolean)?) {
        var lastReached = true
        // each leg's end and whether it reached its goal: a route is reached when the leg its way ends with did (SafePath.routeOn tries
        // more legs than it keeps - the walk off before the turn - so the last leg asked is not always the one the way ends with)
        val legEnds = ArrayList<Pair<DoubleArray, Boolean>>()
        fun reachedBy(way: List<DoubleArray>?): Boolean = way?.lastOrNull()?.let { e -> legEnds.lastOrNull { (p, _) -> p[0] == e[0] && p[1] == e[1] }?.second } ?: lastReached
        var links: List<Climb.Link> = emptyList()
        val leg: (DoubleArray, DoubleArray) -> List<DoubleArray>? = { a, b -> Climb.route(cg, a, b, danger, k, deep, links)?.also { lastReached = it.reached; it.pts.lastOrNull()?.let { e -> legEnds.add(e to it.reached) } }?.pts }
        // the narrow-stream ruling's other half: no wade longer than SafePath.NARROW_YD on end. A route that wades longer (along a stream,
        // Felwood: 2.3 yd for 12 yd) is routed again with no wading at all
        // (deeper than the ford by the grid's vertices or by the liquid's surface over the ground at the spot itself, rawDeep, as
        // py/wetcheck.py reads it: the grid keeps tenths, and Loch Modan's route C walked 13 yd along a stream 1.51-1.56 deep the grid read 1.5)
        fun overFord(x: Double, y: Double): Boolean { val d = waterAt(x, y)
            return !deckAt(x, y) && ((rawDeep != null && rawDeep(x, y)) || (!d.isNaN() && d != SafePath.Water.BRIDGE && d > SafePath.FORD_DEPTH)) }
        val strictDeep: (Double, Double) -> Boolean = { x, y -> overFord(x, y) }
        val strictLeg: (DoubleArray, DoubleArray) -> List<DoubleArray>? = { a, b -> Climb.route(cg, a, b, danger, k, strictDeep, links)?.also { lastReached = it.reached; it.pts.lastOrNull()?.let { e -> legEnds.add(e to it.reached) } }?.pts }
        fun longestWade(w: List<DoubleArray>): Double {
            var run = 0.0; var best = 0.0
            for ((pa, pb) in w.zipWithNext()) { if (Climb.inCave(pa) || Climb.inCave(pb)) { run = 0.0; continue }
                val n = maxOf(1, (Math.hypot(pb[0] - pa[0], pb[1] - pa[1]) * 2).toInt())
                for (q in 0 until n) { val x = pa[0] + (pb[0] - pa[0]) * q / n; val y = pa[1] + (pb[1] - pa[1]) * q / n
                    if (overFord(x, y)) { run += 0.5; best = maxOf(best, run) } else run = 0.0 } }
            return best
        }
        // the same with no danger counted (the detour cap's rival: SafePath.capDetour)
        val calmLeg: (DoubleArray, DoubleArray) -> List<DoubleArray>? = { a, b -> Climb.route(cg, a, b, calm, k, deep, links)?.pts }

    /** route [i]'s way from [f] to [gp] on [netC]; [lastReached] says whether it reached its goal */
    fun plan(netC: SafePath.Net, f: DoubleArray, gp: DoubleArray, clear: (DoubleArray, DoubleArray) -> Boolean, i: Int, println: (String) -> Unit): List<DoubleArray> {
            lastReached = true; legEnds.clear()
            val way0 = SafePath.routeOn(netC, f!![0], f[1], gp!![0], gp[1], leg, clear = clear, walkable = { a, b -> Climb.walkable(cg, a, b, deep) }) ?: leg(f, gp) ?: listOf(f, gp)
            var reached0 = reachedBy(way0)
            val wayW = if (longestWade(way0) <= SafePath.NARROW_YD) way0 else {
                println("WADE route ${('A' + i)}: a wade ${longestWade(way0)} yd on end - routed again with no wading")
                (SafePath.routeOn(netC, f[0], f[1], gp[0], gp[1], strictLeg, clear = clear, walkable = { a, b -> Climb.walkable(cg, a, b, strictDeep) }) ?: strictLeg(f, gp))
                    ?.also { reached0 = reachedBy(it) } ?: way0 }
            val way = if (!reached0) wayW else {
                val direct = calmLeg(f, gp)?.takeIf { d -> d.size >= 2 && Math.hypot(d.last()[0] - gp[0], d.last()[1] - gp[1]) < 1.0 }
                SafePath.capDetour(wayW, direct?.takeIf { longestWade(it) <= SafePath.NARROW_YD }).also { if (it !== wayW) println("DETOUR route ${('A' + i)}: round the danger ${wayW.zipWithNext { a, b -> Math.hypot(b[0] - a[0], b[1] - a[1]) }.sum().toInt()} yd, capped: the way ignoring it ${it.zipWithNext { a, b -> Math.hypot(b[0] - a[0], b[1] - a[1]) }.sum().toInt()} yd") }
            }
            lastReached = reached0
            return way
    }
}
