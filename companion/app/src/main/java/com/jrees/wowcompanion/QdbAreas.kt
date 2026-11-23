package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * QUEST OBJECTIVE AREAS FROM QUESTIEDB (pure: tools/map_test). The user, 2026-10-07: "I feel like we can do a butter job of making
 * the quest objective area with the questie db". Quests' add-on sends every QuestieDB spawn spot of each tracked quest's objective on
 * the current map ({"type":"qdbareas"}); here they become areas the way a player reads them: spots near each other (within
 * [JOIN_YD]) form one group, and each group is drawn as the hull of its spots, each spot widened to a circle of [PAD_YD] (a lone
 * spot or a pair: [SMALL_YD]), so it reads as a rounded blob hugging where the mobs / objects are. The spots are MEASURED
 * (QuestieDB Forever: Forever's own spawns); the distances are choices.
 */
object QdbAreas {
    const val JOIN_YD = 45.0          // spots this near (or chained this near) are one group
    const val PAD_YD = 15.0           // each spot widened by this, so the blob hugs but does not cut through the camp
    const val SMALL_YD = 25.0         // a group of one or two spots: a round area this wide
    private const val CIRCLE_N = 12   // the widening circle's points

    /** one objective's spots on a map: quest [qid], objective [obj], spots [pts] (x, y in the map's 0..1) */
    class Obj(val qid: Int, val obj: Int, val pts: List<DoubleArray>) {
        /** #153: its [groups] for the last two scales asked ([groupsOf]), newest first */
        @Volatile internal var memo: List<Memo> = emptyList()
    }
    internal class Memo(val ax: Double, val ay: Double, val groups: List<Group>)
    class Areas(val m: Int, val set: Int, val objs: List<Obj>)

    /** a {"type":"qdbareas"} document, or null (an error, no map); spots without both coordinates are left out */
    fun parse(d: JSONObject): Areas? {
        if (d.has("err") || !d.has("m")) return null
        val out = ArrayList<Obj>()
        d.optJSONArray("q")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                val qid = e.optInt("i", 0)
                if (qid <= 0) continue
                val pts = ArrayList<DoubleArray>()
                e.optJSONArray("p")?.let { p ->
                    for (j in 0 until p.length()) {
                        val xy = p.optJSONArray(j) ?: continue
                        val x = xy.optDouble(0, Double.NaN); val y = xy.optDouble(1, Double.NaN)
                        if (!x.isNaN() && !y.isNaN()) pts.add(doubleArrayOf(x, y))
                    }
                }
                if (pts.isNotEmpty()) out.add(Obj(qid, e.optInt("o", 0), pts))
            }
        }
        return Areas(d.optInt("m"), d.optInt("set", 0), out)
    }

    /** one group's area: its closed [outline] (x, y in 0..1, counter-clockwise) and how many spots made it */
    class Group(val outline: List<DoubleArray>, val n: Int)

    /**
     * The areas of one objective's [pts] (x, y in a map's 0..1), [ax] / [ay] yards per unit across / down: one closed outline per
     * group (its points in 0..1, counter-clockwise), the biggest group first.
     */
    fun shapes(pts: List<DoubleArray>, ax: Double, ay: Double): List<List<DoubleArray>> = groups(pts, ax, ay).map { it.outline }

    /** a group of at most this many spots is SMALL (a lone spawn or a pair) */
    const val SMALL_GROUP = 2

    /**
     * Which of a quest's groups (all its objectives') are drawn (UI Art, after the first frame: on an outlined quest a scatter of
     * one- and two-spawn rings read as the dots the user ruled out): the FOCUSED quest keeps every group; an outlined one leaves out
     * its small groups when it has a bigger one (the big areas say where to go); a quest with only small groups keeps them.
     */
    fun keep(groups: List<Group>, focused: Boolean): List<Group> =
        if (focused || groups.none { it.n > SMALL_GROUP }) groups else groups.filter { it.n > SMALL_GROUP }

    /** the latest areas per map, shared: Home's map receives them (Quests' qdbareas), the Map room draws the same map's */
    private val latest = LinkedHashMap<Int, Areas>()
    fun remember(a: Areas) = synchronized(latest) {
        latest.remove(a.m); latest[a.m] = a
        while (latest.size > 8) latest.remove(latest.keys.first())
    }
    fun latestFor(m: Int): Areas? = synchronized(latest) { latest[m] }

    /** an area's least width on screen, px (UI Art: thinner, a lone ring or a line of spawns reads as a marker / a dot) */
    const val FLOOR_PX = 30f

    /**
     * How much an area whose widest side is [span] px on screen is scaled about its middle: up to [FLOOR_PX], never down, so a
     * line of spawns stays a long shape, just wide enough to read (UI Art, areas_preview_v2). Every QuestieDB area, the focused
     * quest's filled ones too (an 18 px filled area is a dot, which the user ruled out).
     */
    fun floorK(span: Float): Float = if (span > 0f && span < FLOOR_PX) FLOOR_PX / span else 1f

    /** [shapes] with each group's spot count, the biggest first */
    fun groups(pts: List<DoubleArray>, ax: Double, ay: Double): List<Group> {
        if (pts.isEmpty() || ax <= 0.0 || ay <= 0.0) return emptyList()
        // in yards, so "near" is measured on the ground (a zone map is not square)
        val yd = pts.map { doubleArrayOf(it[0] * ax, it[1] * ay) }
        val parent = IntArray(yd.size) { it }
        fun root(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; var k = i; while (parent[k] != r) { val n = parent[k]; parent[k] = r; k = n }; return r }
        // #153 (an ANR inside this loop on the UI thread): only the spots in the same or a neighbouring JOIN_YD cell are compared -
        // every pair within JOIN_YD is among them, so the same groups as every spot against every other ([groupsPairwise]), in about
        // n steps instead of n * n
        fun cell(cx: Long, cy: Long): Long = (cx shl 32) xor (cy and 0xffffffffL)
        val cx = LongArray(yd.size) { Math.floor(yd[it][0] / JOIN_YD).toLong() }
        val cy = LongArray(yd.size) { Math.floor(yd[it][1] / JOIN_YD).toLong() }
        val cells = HashMap<Long, MutableList<Int>>()
        for (i in yd.indices) cells.getOrPut(cell(cx[i], cy[i])) { ArrayList() }.add(i)
        for (i in yd.indices) for (dx in -1L..1L) for (dy in -1L..1L) {
            val near = cells[cell(cx[i] + dx, cy[i] + dy)] ?: continue
            for (j in near) {
                if (j <= i) continue
                if (Math.hypot(yd[i][0] - yd[j][0], yd[i][1] - yd[j][1]) <= JOIN_YD) { val a = root(i); val b = root(j); if (a != b) parent[a] = b }
            }
        }
        return outlines(yd, ax, ay) { root(it) }
    }

    /** the groups' outlines from [yd] (yards) and each spot's group [rootOf], the biggest group first */
    private fun outlines(yd: List<DoubleArray>, ax: Double, ay: Double, rootOf: (Int) -> Int): List<Group> {
        val groups = yd.indices.groupBy { rootOf(it) }.values.sortedByDescending { it.size }
        return groups.map { g ->
            val r = if (g.size <= 2) SMALL_YD else PAD_YD
            val ring = ArrayList<DoubleArray>(g.size * CIRCLE_N)
            for (i in g) for (k in 0 until CIRCLE_N) {
                val t = 2.0 * Math.PI * k / CIRCLE_N
                ring.add(doubleArrayOf(yd[i][0] + r * Math.cos(t), yd[i][1] + r * Math.sin(t)))
            }
            Group(hull(ring).map { doubleArrayOf(it[0] / ax, it[1] / ay) }, g.size)
        }
    }

    /** the old grouping, every spot against every other (the tests: [groups] must give the same groups, and how much faster) */
    internal fun groupsPairwise(pts: List<DoubleArray>, ax: Double, ay: Double): List<Group> {
        if (pts.isEmpty() || ax <= 0.0 || ay <= 0.0) return emptyList()
        val yd = pts.map { doubleArrayOf(it[0] * ax, it[1] * ay) }
        val parent = IntArray(yd.size) { it }
        fun root(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; var k = i; while (parent[k] != r) { val n = parent[k]; parent[k] = r; k = n }; return r }
        for (i in yd.indices) for (j in i + 1 until yd.size) {
            if (Math.hypot(yd[i][0] - yd[j][0], yd[i][1] - yd[j][1]) <= JOIN_YD) { val a = root(i); val b = root(j); if (a != b) parent[a] = b }
        }
        return outlines(yd, ax, ay) { root(it) }
    }

    // ---------------------------------------------------------------- #153: the groups cached, the big ones worked out off the UI thread
    /**
     * THE AREAS' GROUPS, CACHED AND OFF THE UI THREAD (#153, 1.0.37: an ANR with the UI thread in [groups] from MapView.deriveQuestAreas,
     * which ran it again for every quest of every quests document; the Map room ran it on every frame). An objective's groups are kept
     * per scale ([Obj.memo], the last two: Home's map and the Map room may ask with their own); an objective of at most [INLINE_MAX]
     * spots is worked out where it is asked (well under a millisecond), a bigger one on the areas thread ("WCQDB"), and the asker is
     * told when it is ready ([groupsFor]'s ready, on that thread: the view posts its redraw). Pure (no Android): the tests drive it.
     */
    const val INLINE_MAX = 300

    /** [o]'s groups at this scale when already worked out, else null */
    fun cached(o: Obj, ax: Double, ay: Double): List<Group>? = o.memo.firstOrNull { it.ax == ax && it.ay == ay }?.groups

    /** [o]'s groups at this scale, worked out now when not cached (any thread) and kept */
    fun groupsOf(o: Obj, ax: Double, ay: Double): List<Group> {
        cached(o, ax, ay)?.let { return it }
        val g = groups(o.pts, ax, ay)
        o.memo = (listOf(Memo(ax, ay, g)) + o.memo.filter { it.ax != ax || it.ay != ay }).take(2)
        return g
    }

    /**
     * Quest [qid]'s groups (all its objectives in [a]) at this scale, or null while a big objective is still being worked out on the
     * areas thread; [ready] runs (on that thread) once it is, so the asker draws again. Small objectives are worked out at once.
     */
    fun groupsFor(a: Areas, qid: Int, ax: Double, ay: Double, ready: () -> Unit): List<Group>? {
        var missing = false
        val out = ArrayList<Group>()
        for (o in a.objs) {
            if (o.qid != qid) continue
            val c = cached(o, ax, ay) ?: if (o.pts.size <= INLINE_MAX) groupsOf(o, ax, ay) else null
            if (c == null) missing = true else out.addAll(c)
        }
        if (!missing) return out
        prepare(a, ax, ay, ready)
        return null
    }

    private class Job(val a: Areas, val ax: Double, val ay: Double) {
        override fun equals(other: Any?) = other is Job && other.a === a && other.ax == ax && other.ay == ay
        override fun hashCode() = System.identityHashCode(a) * 31 + ax.hashCode() * 17 + ay.hashCode()
    }
    private val jobs = HashMap<Job, MutableList<() -> Unit>>()
    private val worker by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "WCQDB").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 } }
    }
    /** how long the last document took on the areas thread, ms (-1 = none yet; the tests) */
    @Volatile var lastPrepareMs = -1L
        private set

    /** every objective of [a] worked out at this scale on the areas thread, then [done] (there); one job per document and scale */
    fun prepare(a: Areas, ax: Double, ay: Double, done: () -> Unit) {
        if (a.objs.all { cached(it, ax, ay) != null }) { done(); return }
        val job = Job(a, ax, ay)
        synchronized(jobs) {
            val waiting = jobs[job]
            if (waiting != null) { waiting.add(done); return }
            jobs[job] = arrayListOf(done)
        }
        worker.execute {
            val t0 = System.nanoTime()
            runCatching { for (o in a.objs) groupsOf(o, ax, ay) }
            lastPrepareMs = (System.nanoTime() - t0) / 1_000_000L
            val told = synchronized(jobs) { jobs.remove(job) }.orEmpty()
            for (f in told) runCatching { f() }
        }
    }

    /** the convex hull of [p] (Andrew's monotone chain), counter-clockwise, no repeated end point */
    fun hull(p: List<DoubleArray>): List<DoubleArray> {
        if (p.size < 3) return p
        val s = p.sortedWith(compareBy({ it[0] }, { it[1] }))
        fun cross(o: DoubleArray, a: DoubleArray, b: DoubleArray) = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        val lower = ArrayList<DoubleArray>()
        for (q in s) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], q) <= 0) lower.removeAt(lower.size - 1); lower.add(q) }
        val upper = ArrayList<DoubleArray>()
        for (q in s.asReversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], q) <= 0) upper.removeAt(upper.size - 1); upper.add(q) }
        lower.removeAt(lower.size - 1); upper.removeAt(upper.size - 1)
        return lower + upper
    }

    /** [x], [y] inside the closed outline [poly] (even-odd) */
    fun inside(poly: List<DoubleArray>, x: Double, y: Double): Boolean {
        var c = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a[1] > y) != (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) c = !c
            j = i
        }
        return c
    }
}
