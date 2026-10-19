package com.jrees.wowcompanion

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.PriorityQueue
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * THE GROUND AT ITS OWN GRAIN (b-route-climb; the user on Durotar's test routes: "Some of the paths are still going through a wall (for
 * example b6 to b7)"; Durotar's hills are clusters of steep cones, and the 25 yd slope grid's MEAN slope blurred a cone's wall into a
 * climb). The terrain's own vertex lattice (4.2 yd: an ADT's outer heights): between each vertex and its east and south neighbours, the
 * step's class - level (under 20 degrees), a climb (to 35), steep (to 45), a WALL (45 or steeper, or nothing known: the user, "I cant
 * walk up walls no matter how much you seem to think I can" - Classic stops a climb near 50 and slides a player earlier, so 45 with
 * the margin; a wall is never crossed, whatever it would save, and the gentler classes cost only a little more). A route's leg is planned
 * on it (A* by the lattice, its bends straightened only along a line of sight that never crosses a wall), and a goal on ground no
 * player can reach (a cone's top) is walked to the nearest spot one can stand on. Built by the terrain step from the heights it reads;
 * kept on the device only (terrain/<map>.clb). Pure but for the file.
 */
object Climb {
    const val STEP = 100.0 / 3.0 / 8.0          // the lattice: an ADT chunk's 8 cells
    private const val ORG = 32.0 * MapHarvest.ADT_YD
    /** the map's corner exact (32 ADTs of 1600/3 yd), for where a point falls within a cell (its face, its floor's height): the lattice's
     *  own [ORG] (MapHarvest.ADT_YD's 533.33333) is 0.0001 yd off the game's, and a sample 0.0002 yd from a triangle's edge took the face
     *  beside it (Durotar G8: the level west face, where the game's own and py/segcheck.py's is the 47.9 degree south one) */
    private const val ORG_EXACT = 32.0 * 1600.0 / 3.0
    /** a lattice step's water samples: every half yard along it, a side step's and a diagonal's (route's water check, as [walkable]'s) */
    const val LEVEL = 0; const val CLIMB = 1; const val STEEP = 2; const val WALL = 3
    const val CLIMB_DEG = 20.0; const val STEEP_DEG = 35.0; const val WALL_DEG = 45.0
    /** THE SHORT STEEP STEP (the user, 2026-10-18: "Allow short steps to 48°"): one lattice step, or a straight line's stretch of faces no
     *  longer than [SHORT_STEEP_YD], under this is walked (Feralas' Woodpaw Den, 45.0; the Grimtotem ramp, 46.5-47.1); a run of them, or
     *  anything steeper, stays a wall (Jasperlode's 50-64 degree cliff) */
    const val SHORT_STEEP_DEG = 48.0
    const val SHORT_STEEP_YD = 2.0
    /** a step's cost per yard by its class (a wall: none) */
    val COST = doubleArrayOf(1.0, 1.3, 2.0, Double.NaN)

    fun classOf(dh: Float, run: Double = STEP, wallDeg: Double = WALL_DEG): Int {
        if (dh.isNaN()) return WALL
        val deg = Math.toDegrees(atan(abs(dh) / run))
        return when { deg < CLIMB_DEG -> LEVEL; deg < STEEP_DEG -> CLIMB; deg < wallDeg -> STEEP; else -> WALL }
    }

    /**
     * one zone's lattice: its first vertex at global row [gr0] / column [gc0] (rows south from the map's top, columns east), [nx] x [ny]
     * vertices; [cls] each vertex's east step (bits 0-1) and south step (bits 2-3)
     */
    class Grid(val gr0: Int, val gc0: Int, val nx: Int, val ny: Int, val cls: ByteArray, val faces: ByteArray? = null) {
        /** the class of the terrain triangle under world ([wx], [wy]) ([faces]: each cell's north, east, south, west triangles, 2 bits
         *  each); LEVEL when the faces are not known, a WALL off the grid */
        fun faceAt(wx: Double, wy: Double): Int {
            val f = faces ?: return LEVEL
            val gx = (ORG_EXACT - wy) / STEP - gc0; val gy = (ORG_EXACT - wx) / STEP - gr0
            val i = floor(gx).toInt(); val j = floor(gy).toInt()
            if (i < 0 || j < 0 || i >= nx || j >= ny) return WALL
            val fx = gx - i; val fy = gy - j
            val t = when { fy <= fx && fy <= 1 - fx -> 0; fx >= fy && fx >= 1 - fy -> 1; fy >= fx && fy >= 1 - fx -> 2; else -> 3 }
            return (f[j * nx + i].toInt() shr (2 * t)) and 3
        }
        /** the straight line from world [a] to [b] crosses no wall face (sampled each half yard; at each, the gentlest face within a
         *  quarter yard - a line along an edge is walked on its gentler side) */
        fun lineClear(a: DoubleArray, b: DoubleArray): Boolean {
            val len = hypot(b[0] - a[0], b[1] - a[1])
            val n = max(1, (len / 0.5).toInt())
            // NO CUTTING THROUGH A BUILDING (the user, 2026-10-18: "No cutting through"): a straight line longer than a step never crosses
            // a room's footprint ([solid]); the steps into one (an inn, a trainer's hall) stay open
            val sol = solid
            if (sol != null && len > SOLID_LINE_YD) for (q in 0..n) { if (sol(a[0] + (b[0] - a[0]) * q / n, a[1] + (b[1] - a[1]) * q / n)) return false }
            if (faces == null) return true
            var run = 0.0; val step = len / n
            for (q in 0..n) {
                val x = a[0] + (b[0] - a[0]) * q / n; val y = a[1] + (b[1] - a[1]) * q / n
                if (minOf(faceAt(x, y), faceAt(x + 0.25, y), faceAt(x - 0.25, y), faceAt(x, y + 0.25), faceAt(x, y - 0.25)) != WALL) { run = 0.0; continue }
                // walls all round: a short stretch of 45-48 degree faces is walked (SHORT_STEEP_YD), anything steeper or longer is a wall
                if (!(short48(x, y) || short48(x + 0.25, y) || short48(x - 0.25, y) || short48(x, y + 0.25) || short48(x, y - 0.25))) return false
                run += step; if (run > SHORT_STEEP_YD) return false
            }
            return floorClear(a, b, n)
        }
        /** the floor a walker is on at each vertex ([floorLayers]' height; NaN off every floor) and whether it stands more than a step
         *  ([Caves.STEP_UP_YD]) over its own ground there; null when the lattice was built with no floors (the device's) */
        var deckZ: FloatArray? = null
        var deckHigh: BooleanArray? = null
        /** the line's own rise on a floor, as py/segcheck.py walks it: where a sample's cell has its four corners on a floor, the floor's
         *  height there (straight between them) against the height 2 yd back along the line; 45 degrees or more is a wall. The lattice
         *  classes a diagonal by the easier of its corner pairs, and a floor sloping 49 degrees across a cell reads 42 and 37 along its
         *  sides (Stormwind's harbour ramp; Shen'dralar's stair, 59 along the line) */
        private fun floorClear(a: DoubleArray, b: DoubleArray, n: Int): Boolean {
            val dz = deckZ ?: return true
            val len = hypot(b[0] - a[0], b[1] - a[1]); val step = len / n
            val win = max(1, Math.round(2.0 / max(step, 1e-6)).toInt())
            val zs = DoubleArray(n + 1); var runFrom = -1; var steep = 0.0
            for (q in 0..n) {
                val x = a[0] + (b[0] - a[0]) * q / n; val y = a[1] + (b[1] - a[1]) * q / n
                val gx = (ORG_EXACT - y) / STEP - gc0; val gy = (ORG_EXACT - x) / STEP - gr0
                val i = floor(gx).toInt(); val j = floor(gy).toInt()
                val z = if (i < 0 || j < 0 || i + 1 >= nx || j + 1 >= ny) Double.NaN else {
                    val v0 = dz[j * nx + i]; val v1 = dz[j * nx + i + 1]; val v2 = dz[(j + 1) * nx + i]; val v3 = dz[(j + 1) * nx + i + 1]
                    val fc = gx - i; val fr = gy - j
                    ((v0 * (1 - fc) + v1 * fc) * (1 - fr) + (v2 * (1 - fc) + v3 * fc) * fr)      // NaN when a corner is off the floor
                }
                if (z.isNaN()) { runFrom = -1; steep = 0.0; continue }
                if (runFrom < 0) runFrom = q
                zs[q] = z
                if (q - runFrom < win) continue
                // the user (2026-10-19, "Yes, floors too"): a floor's short steep stretch, 45-48 degrees for no more than SHORT_STEEP_YD, is
                // walked as on the terrain; 48 degrees or more, or a longer stretch, is a wall (segcheck's line_walk the same)
                val deg = Math.toDegrees(atan(abs(z - zs[q - win]) / (win * step)))
                if (deg >= SHORT_STEEP_DEG) return false
                if (deg >= WALL_DEG) { steep += step; if (steep > SHORT_STEEP_YD) return false } else steep = 0.0
            }
            return true
        }
        /** the faces 45-48 degrees steep (each cell's four, a bit each: [SHORT_STEEP_DEG]); null when not read */
        var faces48: ByteArray? = null
        /** the face under world ([wx], [wy]) a wall under [SHORT_STEEP_DEG] */
        fun short48(wx: Double, wy: Double): Boolean {
            val f = faces48 ?: return false
            val gx = (ORG_EXACT - wy) / STEP - gc0; val gy = (ORG_EXACT - wx) / STEP - gr0
            val i = floor(gx).toInt(); val j = floor(gy).toInt()
            if (i < 0 || j < 0 || i >= nx || j >= ny) return false
            val fx = gx - i; val fy = gy - j
            val t = when { fy <= fx && fy <= 1 - fx -> 0; fx >= fy && fx >= 1 - fy -> 1; fy >= fx && fy >= 1 - fx -> 2; else -> 3 }
            return (f[j * nx + i].toInt() shr t) and 1 != 0
        }
        /** a building's room footprint at world (x, y) (the caller's: WorldBuildings.feet), never cut through by a straight line */
        var solid: ((Double, Double) -> Boolean)? = null
        /** each vertex's yards to the nearest DROP (a wall step where the ground falls away: [dropsOf]), in tenths, 255 = none within
         *  [EDGE_YD]; null when not known (a lattice read from a file that keeps no heights) */
        var dropD: ByteArray? = null
        /** the yards from vertex ([i], [j]) to the nearest drop, [Double.MAX_VALUE] none near (or not known) */
        fun dropYd(i: Int, j: Int): Double {
            val d = dropD ?: return Double.MAX_VALUE
            if (i < 0 || j < 0 || i >= nx || j >= ny) return Double.MAX_VALUE
            val v = d[j * nx + i].toInt() and 0xFF
            return if (v == 255) Double.MAX_VALUE else v / 10.0
        }
        fun wx(j: Int) = ORG - (gr0 + j) * STEP
        fun wy(i: Int) = ORG - (gc0 + i) * STEP
        /** the vertex nearest world ([wx], [wy]) (column, row), clamped onto the grid */
        fun at(wx: Double, wy: Double): IntArray = intArrayOf((((ORG - wy) / STEP).let { Math.round(it).toInt() } - gc0).coerceIn(0, nx - 1),
            (((ORG - wx) / STEP).let { Math.round(it).toInt() } - gr0).coerceIn(0, ny - 1))
        fun east(i: Int, j: Int): Int = if (i < 0 || j < 0 || i >= nx - 1 || j >= ny) WALL else cls[j * nx + i].toInt() and 3
        fun south(i: Int, j: Int): Int = if (i < 0 || j < 0 || i >= nx || j >= ny - 1) WALL else (cls[j * nx + i].toInt() shr 2) and 3
        /** the class of the step from (i, j) by (di, dj), one of the eight: a diagonal by the easier of its two corners' pairs */
        fun step(i: Int, j: Int, di: Int, dj: Int): Int {
            fun h(a: Int, b: Int, d: Int) = if (d > 0) east(a, b) else east(a - 1, b)       // the east-west step from (a, b) by d
            fun v(a: Int, b: Int, d: Int) = if (d > 0) south(a, b) else south(a, b - 1)
            return when {
                dj == 0 -> h(i, j, di)
                di == 0 -> v(i, j, dj)
                else -> {
                    // A FLOOR'S EDGE ACROSS THE CELL: a diagonal cuts through the cell's middle, not by the corner its pair of steps is
                    // classed by. With an end on a floor, some of the cell's four corners off it, and a floor corner standing more than a
                    // step over its own ground, the line steps on or off the floor across its edge where it stands over the ground: a wall
                    // (a step off it is one at a vertex too; a line on the ground at both ends past a floor's corner stays the ground's).
                    // Wetlands' dig platform (the line cut past the ramp's foot onto the platform's side, 3.5 yd up), the Hinterlands'
                    // rope bridge and Dustwallow's (off the deck's side 3-4 yd over the ground)
                    val dz = deckZ; val dh = deckHigh
                    if (dz != null && dh != null) {
                        val ks = intArrayOf(j * nx + i, j * nx + i + di, (j + dj) * nx + i, (j + dj) * nx + i + di)
                        if (i + di in 0 until nx && j + dj in 0 until ny && i in 0 until nx && j in 0 until ny) {
                            val on = ks.count { !dz[it].isNaN() }
                            if (on in 1..3 && (!dz[ks[0]].isNaN() || !dz[ks[3]].isNaN()) && ks.any { dh[it] }) return WALL
                        }
                    }
                    val corners = min(max(h(i, j, di), v(i + di, j, dj)), max(v(i, j, dj), h(i, j + dj, di)))
                    // straight across the cell, through its middle vertex: each half of the diagonal walked on the gentler face beside it
                    val f = faces ?: return corners
                    val ci = min(i, i + di); val cj = min(j, j + dj)
                    if (ci < 0 || cj < 0 || ci >= nx || cj >= ny) return WALL
                    val c = f[cj * nx + ci].toInt(); val nN = c and 3; val nE = (c shr 2) and 3; val nS = (c shr 4) and 3; val nW = (c shr 6) and 3
                    val across = if (di == dj) max(min(nN, nW), min(nE, nS)) else max(min(nN, nE), min(nS, nW))
                    max(corners, across)
                }
            }
        }
        fun write(f: File) {
            f.parentFile?.mkdirs()
            DataOutputStream(GZIPOutputStream(f.outputStream().buffered())).use { o ->
                o.writeInt(MAGIC); o.writeInt(gr0); o.writeInt(gc0); o.writeInt(nx); o.writeInt(ny); o.write(cls)
                o.write(faces ?: ByteArray(nx * ny))
            }
        }
        companion object {
            const val MAGIC = 0x434C4232                       // "CLB2": the steps, then the cells' faces
            fun read(f: File): Grid? = runCatching {
                DataInputStream(GZIPInputStream(f.inputStream().buffered())).use { i ->
                    if (i.readInt() != MAGIC) return null
                    val gr0 = i.readInt(); val gc0 = i.readInt(); val nx = i.readInt(); val ny = i.readInt()
                    Grid(gr0, gc0, nx, ny, ByteArray(nx * ny).also { i.readFully(it) }, ByteArray(nx * ny).also { i.readFully(it) })
                }
            }.getOrNull()
        }
    }

    /**
     * THE FLOOR A WALKER IS ON, at each vertex of the lattice over world rect [t] / [b] / [l] / [r] under a walkway's floors (a bridge's
     * deck, its arches and parapets; a tunnel's floor under its hill; a pier's planks): only one height a vertex, and of a bridge's
     * layers the one the walker reaches - from where a floor meets the ground ([ground]: global row, column -> the terrain's height, NaN
     * none) within [Caves.STEP_UP_YD] (its approach, a tunnel's mouth, a pier's root), on along the floors from vertex to vertex within
     * a step's 45 degrees, the floor reached first (of those reached as soon, the highest: a deck over its supports), never one more than [LAYER_UNDER_YD] under the terrain,
     * pieces of fewer than [MIN_FLOOR_PIECE] vertices dropped.
     * A floor never reached so (a roof, a parapet's top, the arches under the road) is no ground; neither is a tunnel's floor
     * under the hillside but through its mouths. [floors] (wx, wy): every floor's height there (empty off them). Thandol Span: "the
     * lowest floor over the ground" walked its arches, 20 yd under the road; Wetlands' Dun Algaz: its leveled floor let a route climb
     * the 60-75 degree hillside over it. Returns each reached vertex's floor height by [vkey].
     */
    fun floorLayers(t: Double, b: Double, l: Double, r: Double, floors: (Double, Double) -> DoubleArray, ground: (Int, Int) -> Float): Map<Long, Float> {
        val gr0 = floor((ORG - t) / STEP).toInt(); val gr1 = ceil((ORG - b) / STEP).toInt()
        val gc0 = floor((ORG - l) / STEP).toInt(); val gc1 = ceil((ORG - r) / STEP).toInt()
        val at = HashMap<Long, DoubleArray>()
        for (gr in gr0..gr1) for (gc in gc0..gc1) { val f = floors(ORG - gr * STEP, ORG - gc * STEP); if (f.isNotEmpty()) at[vkey(gr, gc)] = f }
        val out = HashMap<Long, Float>()
        // NEAREST FIRST, THEN HIGHEST: a vertex holds the floor a walker from the ground reaches first (the flood's yards along the floors
        // from where they meet the ground), and of floors reached as soon, the highest - the walking surface over anything under it
        // (Riverglades' Farholde causeway: its supports' tops, 2.6 yd under the deck, meet the ground where the deck does). Highest
        // first over the whole flood let a building's upper storey, reached by its own stair, claim every vertex over its ground storey:
        // the storey's doors and the room round a start inside were walled in (Zephras' Valanaar, 7704163; the Darnassus and
        // Orgrimmar gates)
        val queue = PriorityQueue<Triple<Double, Float, Long>>(compareBy<Triple<Double, Float, Long>> { it.first }.thenByDescending { it.second })
        for ((k, f) in at) {
            val g = ground((k shr 32).toInt(), k.toInt()); if (g.isNaN()) continue
            // a floor meets the ground at or over it: never one under it (Riverglades: a shipwreck sunk in the riverbed)
            // every floor meeting the ground here is a start; the highest is popped first, so a deck claims its vertices before its supports
            for (h in f) if (h >= g - SEED_UNDER_YD && h - g <= Caves.STEP_UP_YD) queue.add(Triple(0.0, h.toFloat(), k))
        }
        while (queue.isNotEmpty()) {
            val (d, z, k) = queue.poll()
            if (k in out) continue
            out[k] = z
            val gr = (k shr 32).toInt(); val gc = k.toInt()
            for (dr in -1..1) for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val n = vkey(gr + dr, gc + dc); if (n in out) continue
                val f = at[n] ?: continue
                // along the floor within a step's 45 degrees (a ramp, a stair's run), the nearest to this vertex's height - never a floor
                // more than LAYER_UNDER_YD under the terrain (Azshara's ruins, a floor 2-4 yd under the grass; a tunnel's floor under its
                // hill is walked by its cave's link between its mouths, not as a layer)
                val reach = STEP * hypot(dr.toDouble(), dc.toDouble())
                val gn = ground(gr + dr, gc + dc)
                // ... and a rise of more than a step only along a floor BETWEEN the two vertices (a ramp's, a stair's: a floor at the
                // step's middle within a step of the straight line): two floors a sheer side apart are no ramp, however near their
                // vertices' heights (the Southfury bridge: its deck at 38 yd and its gate arch's roof at 43.6 yd, 5.8 yd apart over a
                // diagonal's 5.9 - the flood climbed onto the arches, and the roof, highest first, took the deck under it)
                var mid: DoubleArray? = null
                fun between(h: Double): Boolean {
                    if (abs(h - z) <= Caves.STEP_UP_YD) return true
                    val m = mid ?: floors(ORG - (gr + dr / 2.0) * STEP, ORG - (gc + dc / 2.0) * STEP).also { mid = it }
                    return m.any { abs(it - (h + z) / 2) <= Caves.STEP_UP_YD }
                }
                val h = f.filter { abs(it - z) <= reach && (gn.isNaN() || it >= gn - LAYER_UNDER_YD) && between(it) }.minByOrNull { abs(it - z) } ?: continue
                queue.add(Triple(d + reach, h.toFloat(), n))
            }
        }
        // and no piece of a few vertices (a tower's ledge 3.6 yd over the ground beside it: Riverglades' outlandhumantower02)
        val seen = HashSet<Long>()
        for (k0 in out.keys.toList()) {
            if (k0 in seen) continue
            val piece = ArrayList<Long>(); val st = ArrayDeque<Long>(); st.add(k0); seen.add(k0)
            while (st.isNotEmpty()) { val k = st.removeLast(); piece.add(k); val z = out[k]!!
                val gr = (k shr 32).toInt(); val gc = k.toInt()
                for (dr in -1..1) for (dc in -1..1) { val n = vkey(gr + dr, gc + dc); val zn = out[n] ?: continue
                    if (n !in seen && abs(zn - z) <= STEP * hypot(dr.toDouble(), dc.toDouble())) { seen.add(n); st.add(n) } } }
            if (piece.size < MIN_FLOOR_PIECE) for (k in piece) out.remove(k)
        }
        return out
    }
    /** a floor's piece of fewer lattice vertices than this is no walk (a ledge, a sill) */
    const val MIN_FLOOR_PIECE = 4
    /** a floor meeting the ground no more than this under it (a ramp's foot sunk in its grass) */
    const val SEED_UNDER_YD = 0.5
    /** a floor this far under the terrain or more is under it: no layer (py/segcheck.py's BURIED) */
    const val LAYER_UNDER_YD = 1.0
    /** whether cell ([gr], [gc]) has corners on a floor ([deck] non-NaN) and off it more than one step's 45 degrees apart in height */
    private fun edgeDrop(gr: Int, gc: Int, height: (Int, Int) -> Float, deck: (Double, Double) -> Float): Boolean {
        var on = false; var off = false; var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for ((a, c) in listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)) {
            val d = deck(ORG - (gr + a) * STEP, ORG - (gc + c) * STEP)
            val z = if (d.isNaN()) { off = true; height(gr + a, gc + c) } else { on = true; d }
            if (z.isNaN()) continue
            lo = min(lo, z.toDouble()); hi = max(hi, z.toDouble())
        }
        return on && off && hi - lo > STEP
    }
    /** a lattice vertex's key (global row, column) */
    fun vkey(gr: Int, gc: Int): Long = (gr.toLong() shl 32) or (gc.toLong() and 0xFFFFFFFFL)
    /** the vertex nearest world ([wx], [wy]): its key */
    fun vkeyAt(wx: Double, wy: Double): Long = vkey(Math.round((ORG - wx) / STEP).toInt(), Math.round((ORG - wy) / STEP).toInt())

    /** the lattice over world rect [t] / [b] / [l] / [r] from the vertex heights ([height]: global row, column -> yards; NaN none);
     *  [level] a spot a building's walkway makes level (a city's street, a covered hole: its steps level, whatever the terrain under it);
     *  [deck] the floor height of a walkway's deck (a bridge, a pier) at a spot, NaN off it: its steps classed by that height */
    fun build(t: Double, b: Double, l: Double, r: Double, height: (Int, Int) -> Float, level: ((Double, Double) -> Boolean)? = null,
              hole: ((Int, Int) -> Boolean)? = null, centre: ((Int, Int) -> Float)? = null, deck: ((Double, Double) -> Float)? = null): Grid {
        val gr0 = floor((ORG - t) / STEP).toInt(); val gr1 = ceil((ORG - b) / STEP).toInt()
        val gc0 = floor((ORG - l) / STEP).toInt(); val gc1 = ceil((ORG - r) / STEP).toInt()
        val nx = gc1 - gc0 + 1; val ny = gr1 - gr0 + 1
        val cls = ByteArray(nx * ny)
        val faces = if (centre != null) ByteArray(nx * ny) else null
        val faces48 = if (centre != null) ByteArray(nx * ny) else null          // the faces 45-48 degrees steep (a bit each: SHORT_STEEP_DEG)
        val candE = BooleanArray(nx * ny); val candS = BooleanArray(nx * ny)  // a wall step under SHORT_STEEP_DEG, by its slope alone
        val deckZ = if (deck != null) FloatArray(nx * ny) else null; val deckHigh = if (deck != null) BooleanArray(nx * ny) else null
        for (j in 0 until ny) for (i in 0 until nx) {
            val h = height(gr0 + j, gc0 + i)
            var e = classOf(height(gr0 + j, gc0 + i + 1) - h); var s = classOf(height(gr0 + j + 1, gc0 + i) - h)
            var e48 = classOf(height(gr0 + j, gc0 + i + 1) - h, wallDeg = SHORT_STEEP_DEG); var s48 = classOf(height(gr0 + j + 1, gc0 + i) - h, wallDeg = SHORT_STEEP_DEG)
            // the game's own surface (the user on Elwynn: "Jasperlode mine path literally walks up and down a mountain thay cant be
            // climbed"): each cell four triangles through its middle vertex ([centre]: global row, column of a cell), a ridge between
            // two outer vertices standing on the middle ones. A step along an edge is walked on the gentler of the two faces beside it.
            if (centre != null) {
                val gr = gr0 + j; val gc = gc0 + i
                val hE = height(gr, gc + 1); val hS = height(gr + 1, gc)
                e = maxOf(e, minOf(faceClass(h, hE, centre(gr - 1, gc)), faceClass(h, hE, centre(gr, gc))))
                s = maxOf(s, minOf(faceClass(h, hS, centre(gr, gc - 1)), faceClass(h, hS, centre(gr, gc))))
                e48 = maxOf(e48, minOf(faceClass(h, hE, centre(gr - 1, gc), SHORT_STEEP_DEG), faceClass(h, hE, centre(gr, gc), SHORT_STEEP_DEG)))
                s48 = maxOf(s48, minOf(faceClass(h, hS, centre(gr, gc - 1), SHORT_STEEP_DEG), faceClass(h, hS, centre(gr, gc), SHORT_STEEP_DEG)))
            }
            // a step between two holed cells (the terrain opened into a cave: [hole] global row, column of a cell) has no ground
            if (hole != null) {
                if (hole(gr0 + j - 1, gc0 + i) && hole(gr0 + j, gc0 + i)) { e = WALL; e48 = WALL }
                if (hole(gr0 + j, gc0 + i - 1) && hole(gr0 + j, gc0 + i)) { s = WALL; s48 = WALL }
            }
            if (level != null && (e != LEVEL || s != LEVEL) && level(ORG - (gr0 + j) * STEP, ORG - (gc0 + i) * STEP)) { e = LEVEL; s = LEVEL }
            // a walkway's DECK (a bridge, a pier: its floor triangles, WorldBuildings.Deck; [deck] its floor's height, NaN off it) is the
            // ground where it is, over the water or the drop under it: a step with an end on it is classed by the deck's height there
            // and the other end's (a landing at the bank's height is level, a step off its side into the ravine 20 yd under it a wall).
            // Leveling every step of a deck vertex leveled the steps off its east and south edges and walled its west and north ones
            // (Ratchet's pier and Auberdine's, entered from the shore, unreached)
            if (deck != null) {
                val x = ORG - (gr0 + j) * STEP; val y = ORG - (gc0 + i) * STEP
                val dv = deck(x, y); val dE = deck(x, ORG - (gc0 + i + 1) * STEP); val dS = deck(ORG - (gr0 + j + 1) * STEP, y)
                val sv = if (dv.isNaN()) h else dv
                deckZ!![j * nx + i] = dv; deckHigh!![j * nx + i] = !dv.isNaN() && dv - h > Caves.STEP_UP_YD
                // ... and a step off a floor standing more than a step over its own ground there is off its edge: a wall, however the
                // rise reads over the lattice's 4.17 yd (Stranglethorn's tower base, a 3.9 yd sheer edge read as 43 degrees)
                fun off(dHere: Float, dThere: Float, hHere: Float, hThere: Float) = (!dHere.isNaN() && dThere.isNaN() && dHere - hHere > Caves.STEP_UP_YD) ||
                    (dHere.isNaN() && !dThere.isNaN() && dThere - hThere > Caves.STEP_UP_YD)
                val hE = height(gr0 + j, gc0 + i + 1); val hS = height(gr0 + j + 1, gc0 + i)
                if (!dv.isNaN() || !dE.isNaN()) e = if (off(dv, dE, h, hE)) WALL else classOf((if (dE.isNaN()) hE else dE) - sv)
                if (!dv.isNaN() || !dS.isNaN()) s = if (off(dv, dS, h, hS)) WALL else classOf((if (dS.isNaN()) hS else dS) - sv)
            }
            cls[j * nx + i] = (e or (s shl 2)).toByte()
            // (a step with either end on a floor is classed by the floor's height: never a short steep one)
            fun onDeck(gr: Int, gc: Int) = deck != null && !deck(ORG - gr * STEP, ORG - gc * STEP).isNaN()
            val here = onDeck(gr0 + j, gc0 + i)
            candE[j * nx + i] = e == WALL && e48 != WALL && !here && !onDeck(gr0 + j, gc0 + i + 1)
            candS[j * nx + i] = s == WALL && s48 != WALL && !here && !onDeck(gr0 + j + 1, gc0 + i)
            // the cell's own four triangles (north, east, south, west of its middle vertex): a straight line across it meets them
            if (faces != null) {
                val gr = gr0 + j; val gc = gc0 + i
                val ha = h; val hb = height(gr, gc + 1); val hc = height(gr + 1, gc); val hd = height(gr + 1, gc + 1); val m = centre!!(gr, gc)
                var plain = false
                val f = when {
                    hole?.invoke(gr, gc) == true -> WALL or (WALL shl 2) or (WALL shl 4) or (WALL shl 6)
                    level != null && level(ORG - (gr + 0.5) * STEP, ORG - (gc + 0.5) * STEP) -> 0
                    // a deck's cell when its four corners are on the deck (a cell with one corner on a floor's edge is the terrain's:
                    // its middle rounded onto that corner leveled the hillside beside a tunnel's mouth, Wetlands' route G)
                    deck != null && listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1).all { (a, c) -> !deck(ORG - (gr + a) * STEP, ORG - (gc + c) * STEP).isNaN() } -> 0
                    // a cell on a floor's edge, its corners on the floor and the terrain more than a 45 degree step apart (a platform
                    // 6 yd over the ground beside it): a wall, whatever the terrain's own faces there (Mulgore D cut across one diagonally)
                    deck != null && edgeDrop(gr, gc, height, deck) -> WALL or (WALL shl 2) or (WALL shl 4) or (WALL shl 6)
                    else -> { plain = true; faceClass(ha, hb, m) or (faceClass(hb, hd, m) shl 2) or (faceClass(hc, hd, m) shl 4) or (faceClass(ha, hc, m) shl 6) }
                }
                faces[j * nx + i] = f.toByte()
                // which of the four are walls under SHORT_STEEP_DEG (a short stretch of them is walked: Grid.lineClear) - the terrain's own faces
                // only: a floor's edge walled over the ground under it takes none (the review's H3: a line spent 2 yd over a 4-6 yd drop)
                if (plain) {
                    val f48 = intArrayOf(faceClass(ha, hb, m, SHORT_STEEP_DEG), faceClass(hb, hd, m, SHORT_STEEP_DEG), faceClass(hc, hd, m, SHORT_STEEP_DEG), faceClass(ha, hc, m, SHORT_STEEP_DEG))
                    var b48 = 0
                    for (q in 0..3) if ((f shr (2 * q)) and 3 == WALL && f48[q] != WALL) b48 = b48 or (1 shl q)
                    faces48!![j * nx + i] = b48.toByte()
                }
            }
        }
        // a short steep step stands alone: a wall step under SHORT_STEEP_DEG walked as steep when the steps before and after it along its
        // own line are no walls (one step up a den's lip; a run of them is a cliff)
        for (j in 0 until ny) for (i in 0 until nx) {
            val k = j * nx + i; var c = cls[k].toInt()
            fun eAt(ii: Int) = if (ii in 0 until nx) cls[j * nx + ii].toInt() and 3 else WALL
            fun sAt(jj: Int) = if (jj in 0 until ny) (cls[jj * nx + i].toInt() shr 2) and 3 else WALL
            if (candE[k] && eAt(i - 1) != WALL && eAt(i + 1) != WALL) c = (c and 0b1100) or STEEP
            if (candS[k] && sAt(j - 1) != WALL && sAt(j + 1) != WALL) c = (c and 0b0011) or (STEEP shl 2)
            cls[k] = c.toByte()
        }
        return Grid(gr0, gc0, nx, ny, cls, faces).also { it.faces48 = faces48; it.deckZ = deckZ; it.deckHigh = deckHigh
            it.dropD = dropsOf(it) { j, i -> deckZ?.get(j * nx + i)?.takeIf { z -> !z.isNaN() } ?: height(gr0 + j, gc0 + i) } }
    }

    /**
     * EDGE CLEARANCE (the user on Zephras, test12: "the path hugged the bottom second of the map so tightly that I almost fell off the
     * island ... it was not easy to traverse and felt like it didn't have a lot of wiggle room to breath from the side of the map"):
     * each vertex's yards to the nearest DROP - a WALL step of [g] (the eight, as the A* takes them) whose far end lies [DROP_YD] or more
     * under its near one, or has no ground at all (a floating isle's rim over the void, a floor's open side over the ground under it:
     * [height] row, column of the grid -> the surface a walker is on there, a floor's where one is) - measured to the step's middle
     * (the cliff's lip lies between the two vertices), within [EDGE_YD]. A wall that RISES from a vertex (a canyon's floor, the foot of a
     * mesa) is none: only the ground falling away is. Tenths of a yard, 255 = none near. ([route] leaves a walkway's own floor out of the
     * cost: a bridge's deck is walked edge to edge.)
     */
    fun dropsOf(g: Grid, height: (Int, Int) -> Float): ByteArray {
        val nx = g.nx; val ny = g.ny
        val out = ByteArray(nx * ny) { 255.toByte() }
        val r = ceil(EDGE_YD / STEP).toInt()
        for (j in 0 until ny) for (i in 0 until nx) {
            val h = height(j, i); if (h.isNaN()) continue
            for (dj in -1..1) for (di in -1..1) {
                if (di == 0 && dj == 0) continue
                val vi = i + di; val vj = j + dj
                if (vi !in 0 until nx || vj !in 0 until ny) continue
                val hn = height(vj, vi)
                if (!hn.isNaN() && h - hn < DROP_YD) continue
                if (g.step(i, j, di, dj) != WALL) continue
                // the drop's lip: half way along the step; every vertex within EDGE_YD of it keeps its nearest such
                val mi = i + di / 2.0; val mj = j + dj / 2.0
                for (b in max(0, j - r)..min(ny - 1, j + r)) for (a in max(0, i - r)..min(nx - 1, i + r)) {
                    val d = hypot(a - mi, b - mj) * STEP; if (d >= EDGE_YD) continue
                    // only the drop's own top: the ground at its foot (the plain under a mesa, the beach under a cliff) is no edge
                    val ha = height(b, a); if (ha.isNaN() || ha < h - DROP_YD) continue
                    val t = Math.round(d * 10).toInt().coerceIn(0, 254)
                    if (t < (out[b * nx + a].toInt() and 0xFF)) out[b * nx + a] = t.toByte()
                }
            }
        }
        return out
    }
    /** EDGE CLEARANCE's reach and weight: a vertex within EDGE_YD of a drop or of deep water costs up to EDGE_COST more a yard, the more the
     *  nearer (straight down to none at EDGE_YD). A cost, never a wall: a ledge path, a bridge, a mountain road along a cliff still walked
     *  where it is the way. 8 yd: the lattice's lip vertex (2.1 yd from the lip) +1.1 a yard, the next in (6.25 yd) +0.33, two in none -
     *  a route along a rim keeps 8-10 yd off it where the ground is open, and no route grows by more than a few yards for it */
    const val EDGE_YD = 8.0
    const val EDGE_COST = 1.5
    /** a wall step falling this far or more is a drop (a fall a player feels or is stranded by); a lower one is a bump */
    const val DROP_YD = 5.0
    /** the extra cost a yard at [d] yards from an edge */
    fun edgeCost(d: Double): Double = if (d >= EDGE_YD) 0.0 else EDGE_COST * (1.0 - d / EDGE_YD)

    /** the class of the triangle on an edge between two outer vertices ([a], [b]: STEP apart) and a cell's middle vertex [m] (half a
     *  STEP off the edge, at its middle): by its face's own slope, its normal's angle from the vertical; NaN (no middle) the edge's own */
    fun faceClass(a: Float, b: Float, m: Float, wallDeg: Double = WALL_DEG): Int {
        if (a.isNaN() || b.isNaN()) return WALL
        if (m.isNaN()) return classOf(b - a, wallDeg = wallDeg)
        // the edge along x (0..STEP, heights a, b), the middle at (STEP / 2, STEP / 2, m): the normal of (STEP, 0, b - a) x (STEP / 2, STEP / 2, m - a)
        val ux = STEP; val uz = (b - a).toDouble(); val vx = STEP / 2; val vy = STEP / 2; val vz = (m - a).toDouble()
        val nx = -uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy
        val deg = Math.toDegrees(Math.acos(abs(nz) / Math.sqrt(nx * nx + ny * ny + nz * nz)))
        return when { deg < CLIMB_DEG -> LEVEL; deg < STEEP_DEG -> CLIMB; deg < wallDeg -> STEEP; else -> WALL }
    }

    /** a planned leg: world points (x, y) from the start; [reached] false when the goal stands on ground no one can walk to (the leg
     *  ends at the nearest spot that can) */
    class Leg(val pts: List<DoubleArray>, val reached: Boolean)

    /** a way the lattice does not see: a cave's or a tunnel's floor between two mouths ([Caves.Way]: world x, y, z points; [yd] along) */
    class Link(val pts: List<DoubleArray>, val yd: Double, val oneWay: Boolean = false)    // oneWay: a portal, an updraft - from its first point only

    /** a point of a leg inside a cave: world x, y and this mark (its ground is the cave's floor, not the terrain over it) */
    const val IN_CAVE = 1.0
    /** a lift's landing on a link's way (a point marked so: "take the lift" there; the ride to the next such point) - inside, as a cave's */
    const val LIFT = 2.0
    /** a point of a declared SWIM link (the user, 2026-10-18, Fenris Isle: "Swim link, flagged"): swum, never walked */
    const val SWIM = 3.0
    fun inCave(p: DoubleArray) = p.size > 2 && (p[2] == IN_CAVE || p[2] == LIFT || p[2] == SWIM)
    fun atSwim(p: DoubleArray) = p.size > 2 && p[2] == SWIM
    fun atLift(p: DoubleArray) = p.size > 2 && p[2] == LIFT

    /**
     * the leg from world [from] to [to] on [g]: A* over the lattice within [MARGIN_YD] of the two, its cost each step's length by its
     * class (a wall never), plus [k] x [danger] there (the danger grid's, 0..4) and [water] a wall (deep water; null = none known);
     * then its bends straightened along lines of sight that cross no wall and cost no more. Null when the start itself is off the grid.
     */
    fun route(g: Grid, from: DoubleArray, to: DoubleArray, danger: (Double, Double) -> Float = { _, _ -> 0f }, k: Double = 0.0,
              water: ((Double, Double) -> Boolean)? = null, links: List<Link> = emptyList(), edges: Boolean = true): Leg? {
        val s = g.at(from[0], from[1]); val e = g.at(to[0], to[1])
        val m = (MARGIN_YD / STEP).toInt()
        val i0 = max(0, min(s[0], e[0]) - m); val i1 = min(g.nx - 1, max(s[0], e[0]) + m)
        val j0 = max(0, min(s[1], e[1]) - m); val j1 = min(g.ny - 1, max(s[1], e[1]) + m)
        val w = i1 - i0 + 1; val hgt = j1 - j0 + 1
        fun id(i: Int, j: Int) = (j - j0) * w + (i - i0)
        val dist = DoubleArray(w * hgt) { Double.MAX_VALUE }; val prev = IntArray(w * hgt) { -1 }
        val stepCost = HashMap<Long, Double>()
        // the mobs a cost, never a wall (the user: "some quests do expect the player to have to kill through mobs to get to the
        // destination"): the danger fades toward the goal, to none at it - over [GOAL_FADE_YD], twice that when the goal's own spot is
        // dangerous (a quest's mobs in a ravine, a cave full of them) - so the way avoids them on the way and goes in straight at the end
        val fade = if (danger(to[0], to[1]) >= GOAL_DANGEROUS) GOAL_FADE_YD * 2 else GOAL_FADE_YD
        // the deep water at each vertex of the window, asked once (0 not yet, 1 dry, 2 deep): its own cost (a wall) and its edge's
        val wet = ByteArray(w * hgt)
        fun deepAt(i: Int, j: Int): Boolean {
            if (water == null || i !in i0..i1 || j !in j0..j1) return false
            val q = (j - j0) * w + (i - i0)
            if (wet[q].toInt() == 0) wet[q] = if (water(g.wx(j), g.wy(i))) 2 else 1
            return wet[q].toInt() == 2
        }
        val er = ceil(EDGE_YD / STEP).toInt()
        // EDGE CLEARANCE ([dropsOf]): a vertex near a drop, or near deep water (its bank: half a step past the last dry vertex), costs more
        // (a walkway's own floor - a bridge's deck, a pier, a rope bridge between Thunder Bluff's rises - is walked edge to edge: its
        // parapets and ropes hold, and its whole width is near its sides; Mulgore G went 376 yd round one)
        val dz = g.deckZ
        fun edgeOf(i: Int, j: Int): Double {
            if (dz != null && !dz[j * g.nx + i].isNaN()) return 0.0
            var d = g.dropYd(i, j)
            if (water != null) for (b in j - er..j + er) for (a in i - er..i + er) {
                if ((a == i && b == j) || !deepAt(a, b)) continue
                d = min(d, max(0.0, hypot((a - i).toDouble(), (b - j).toDouble()) * STEP - STEP / 2))
            }
            return edgeCost(d)
        }
        fun cellCost(i: Int, j: Int): Double {
            val x = g.wx(j); val y = g.wy(i)
            if (deepAt(i, j)) return Double.NaN
            return k * danger(x, y) * (hypot(x - to[0], y - to[1]) / fade).coerceAtMost(1.0) + (if (edges) edgeOf(i, j) else 0.0)
        }
        val extra = DoubleArray(w * hgt) { Double.NaN }
        fun extraOf(i: Int, j: Int): Double { val q = id(i, j); if (extra[q].isNaN()) extra[q] = cellCost(i, j).let { if (it.isNaN()) -1.0 else it }; return extra[q] }
        val start = id(s[0], s[1]); val goal = id(e[0], e[1])
        // the caves' links with both mouths in the window: from a mouth's vertex to the other's, at their floor's length (and danger)
        val jumps = HashMap<Int, ArrayList<IntArray>>()
        val linkCost = DoubleArray(links.size)
        for ((li, lk) in links.withIndex()) {
            val p = g.at(lk.pts.first()[0], lk.pts.first()[1]); val q = g.at(lk.pts.last()[0], lk.pts.last()[1])
            if (p[0] !in i0..i1 || p[1] !in j0..j1 || q[0] !in i0..i1 || q[1] !in j0..j1) continue
            linkCost[li] = lk.yd + k * lk.pts.zipWithNext { a, b -> val mx = (a[0] + b[0]) / 2; val my = (a[1] + b[1]) / 2
                hypot(b[0] - a[0], b[1] - a[1]) * danger(mx, my) * (hypot(mx - to[0], my - to[1]) / fade).coerceAtMost(1.0) }.sum()
            jumps.getOrPut(id(p[0], p[1])) { ArrayList() }.add(intArrayOf(id(q[0], q[1]), li, 0))
            if (!lk.oneWay) jumps.getOrPut(id(q[0], q[1])) { ArrayList() }.add(intArrayOf(id(p[0], p[1]), li, 1))
        }
        val via = IntArray(w * hgt) { -1 }                     // how a vertex was reached: a link (its index x 2 + its way back) or a step
        fun hEst(i: Int, j: Int) = hypot((i - e[0]).toDouble(), (j - e[1]).toDouble()) * STEP
        val open = PriorityQueue<DoubleArray>(compareBy { it[0] })
        dist[start] = 0.0; open.add(doubleArrayOf(hEst(s[0], s[1]), start.toDouble()))
        var best = start; var bestD = hEst(s[0], s[1])
        while (open.isNotEmpty()) {
            val top = open.poll(); val u = top[1].toInt()
            val ui = u % w + i0; val uj = u / w + j0
            if (top[0] - hEst(ui, uj) > dist[u] + 1e-6) continue
            val hd = hEst(ui, uj); if (hd < bestD) { bestD = hd; best = u }
            if (u == goal) break
            for (dj in -1..1) for (di in -1..1) {
                if (di == 0 && dj == 0) continue
                val vi = ui + di; val vj = uj + dj
                if (vi !in i0..i1 || vj !in j0..j1) continue
                val c = g.step(ui, uj, di, dj); if (c == WALL) continue
                // the step's own straight line, as the final check walks it (walkable / segcheck): a turn at a vertex every face within
                // a quarter yard of which is a wall passes the steps beside it but not the line (Tirisfal, Brill -> Agamand Mills)
                // and off deep water along it (a diagonal between two shallow vertices clips a deep cell's corner: Mulgore's route D) - the
                // final check's own test ([walkable]), its two vertices' water left to their own cost
                if (!walkable(g, doubleArrayOf(g.wx(uj), g.wy(ui)), doubleArrayOf(g.wx(vj), g.wy(vi)), water, ends = false)) continue
                val x = extraOf(vi, vj); if (x < 0) continue
                val len = if (di != 0 && dj != 0) STEP * 1.41421356 else STEP
                val v = id(vi, vj)
                val nd = dist[u] + len * (COST[c] + x)
                if (nd < dist[v]) { dist[v] = nd; prev[v] = u; via[v] = -1; open.add(doubleArrayOf(nd + hEst(vi, vj), v.toDouble())) }
            }
            jumps[u]?.forEach { jp ->
                val v = jp[0]; val nd = dist[u] + linkCost[jp[1]]
                if (nd < dist[v]) { dist[v] = nd; prev[v] = u; via[v] = jp[1] * 2 + jp[2]; open.add(doubleArrayOf(nd + hEst(v % w + i0, v / w + j0), v.toDouble())) }
            }
        }
        val end = if (dist[goal] < Double.MAX_VALUE) goal else best
        val path = ArrayList<IntArray>()
        var c = end
        while (c != -1) { path.add(intArrayOf(c % w + i0, c / w + j0, via[c])); c = prev[c] }
        path.reverse()
        if (path.isEmpty()) return null
        // the bends: from each kept vertex, the farthest later one a straight line reaches crossing no wall and costing no more
        fun lineCost(a: IntArray, b: IntArray): Double {
            val n = max(abs(b[0] - a[0]), abs(b[1] - a[1]))
            if (n == 0) return 0.0
            var ci = a[0]; var cj = a[1]; var cost = 0.0
            for (q in 1..n) {
                val ni = a[0] + Math.round((b[0] - a[0]).toDouble() * q / n).toInt(); val nj = a[1] + Math.round((b[1] - a[1]).toDouble() * q / n).toInt()
                val di = sign((ni - ci).toDouble()).toInt(); val dj = sign((nj - cj).toDouble()).toInt()
                if (di == 0 && dj == 0) continue
                val cl = g.step(ci, cj, di, dj); if (cl == WALL) return Double.MAX_VALUE
                if (ni !in i0..i1 || nj !in j0..j1) return Double.MAX_VALUE
                // the straight line itself as the final check walks it ([walkable]: its faces, off deep water every half yard - Redridge J
                // clipped a 3.9 yd cell's corner between 2 yd samples), for EVERY line, one step long too: a bend cut to the vertex next to
                // its start (A* went round a step whose own line is a wall) was taken on its lattice step alone (Duskwood I, Wetlands C)
                if (q == n && !walkable(g, doubleArrayOf(g.wx(a[1]), g.wy(a[0])), doubleArrayOf(g.wx(b[1]), g.wy(b[0])), water, ends = false)) return Double.MAX_VALUE
                val x = extraOf(ni, nj); if (x < 0) return Double.MAX_VALUE
                cost += (if (di != 0 && dj != 0) STEP * 1.41421356 else STEP) * (COST[cl] + x)
                ci = ni; cj = nj
            }
            return cost
        }
        val along = DoubleArray(path.size)
        for (q in 1 until path.size) along[q] = along[q - 1] + lineCost(path[q - 1], path[q]).let { if (it == Double.MAX_VALUE) 0.0 else it }
        // straightened a stretch of terrain at a time: a line never jumps a cave (each link's two mouths kept, its floor between)
        val pts = ArrayList<DoubleArray>()
        var a = 0
        fun vert(q: Int) = doubleArrayOf(g.wx(path[q][1]), g.wy(path[q][0]))
        pts.add(vert(0))
        while (a < path.size - 1) {
            if (path[a + 1][2] >= 0) {
                val lk = links[path[a + 1][2] / 2]
                val way = if (path[a + 1][2] % 2 == 0) lk.pts else lk.pts.reversed()
                for (p in way) pts.add(doubleArrayOf(p[0], p[1], if (p.size > 3 && p[3] == 1.0) LIFT else if (p.size > 3 && p[3] == 2.0) SWIM else IN_CAVE))
                pts.add(vert(a + 1)); a++; continue
            }
            var stop = a + 1
            while (stop < path.size - 1 && path[stop + 1][2] < 0) stop++
            var b = stop
            while (b > a + 1 && lineCost(path[a], path[b]) > (along[b] - along[a]) * 1.03 + 1.0) b--
            pts.add(vert(b)); a = b
        }
        // the leg's own ends: the start's and the goal's vertices kept (the lines were checked from them), the exact points a short hop
        // beyond (within their cell)
        // ... the start's exact point only where the hop from it crosses no wall face (as the goal's: Deadwind's route J began 47 degrees
        // up a slope): else the leg starts at its first vertex
        fun hop(p: DoubleArray, q: DoubleArray) = g.lineClear(p, q) && walkable(g, p, q, water)
        if (hypot(pts[0][0] - from[0], pts[0][1] - from[1]) > 0.5) { if (hop(from, pts[0])) pts.add(0, from) }
        else if (pts.size < 2 || hop(from, pts[1])) pts[0] = from
        val reached = end == goal
        // the goal's exact point only where the hop to it crosses no wall face (a network point 2 yd up a cliff, an unreachable goal):
        // else the leg ends at its last vertex, short of it
        var arrived = reached
        // a goal reached by a link (a portal's, an updraft's, a floor's in the air): the leg ends on the link's own last point
        if (reached && pts.size >= 2 && inCave(pts[pts.size - 2]) && !inCave(pts.last()) && hypot(pts[pts.size - 2][0] - to[0], pts[pts.size - 2][1] - to[1]) <= 3.0)
            pts.removeAt(pts.size - 1)
        if (reached && !inCave(pts[pts.size - 1])) {
            if (hypot(pts.last()[0] - to[0], pts.last()[1] - to[1]) <= 0.5) {
                // even the last half yard re-checked: the point before must reach the goal itself (Darkshore's route J)
                if (pts.size < 2 || hop(pts[pts.size - 2], to)) pts[pts.size - 1] = to else arrived = false
            }
            else if (hop(pts.last(), to)) pts.add(to) else arrived = false
        }
        return Leg(pts, arrived)
    }
    const val MARGIN_YD = 800.0
    /** a straight line longer than this (a diagonal step and a half) is a cut, never through a room ([Grid.solid]) */
    const val SOLID_LINE_YD = STEP * 1.5 * 1.41421356
    const val GOAL_FADE_YD = 100.0
    const val GOAL_DANGEROUS = 1f              // a goal's spot this dangerous (the grid's 0..4) fades its danger over twice as far

    /** whether the straight line from world [a] to [b] on [g] crosses no wall: the lattice's own steps along it (a way the network
     *  drew from the 25 yd cells can still climb a cliff those cells averaged away). THE ONE CHECK: the final route's segments are judged
     *  by it, and every producer of a straight piece (the A* steps, the leg's straightened lines and its end hops, the network's pieces,
     *  unloop) uses it too, so the router never draws a segment its own check rejects. [ends] false leaves the water at the two ends
     *  out (lattice vertices whose water is their own cost) */
    fun walkable(g: Grid, a: DoubleArray, b: DoubleArray, water: ((Double, Double) -> Boolean)? = null, ends: Boolean = true): Boolean {
        if (inCave(a) || inCave(b)) return true                 // a cave's floor: walkable by its own triangles, not the hill over it
        // deep water (the user: "Pathing shouldn't be in water"): a straight piece across it is no walk either (Mulgore's Stonebull Lake:
        // a network way's hooked end cut straight across it)
        if (water != null) {
            val n = max(1, (hypot(b[0] - a[0], b[1] - a[1]) / 0.5).toInt())
            for (q in (if (ends) 0 else 1)..(if (ends) n else n - 1)) if (water(a[0] + (b[0] - a[0]) * q / n, a[1] + (b[1] - a[1]) * q / n)) return false
        }
        val s = g.at(a[0], a[1]); val e = g.at(b[0], b[1])
        val n = max(abs(e[0] - s[0]), abs(e[1] - s[1]))
        var ci = s[0]; var cj = s[1]
        for (q in 1..n) {
            val ni = s[0] + Math.round((e[0] - s[0]).toDouble() * q / n).toInt(); val nj = s[1] + Math.round((e[1] - s[1]).toDouble() * q / n).toInt()
            val di = sign((ni - ci).toDouble()).toInt(); val dj = sign((nj - cj).toDouble()).toInt()
            if (di == 0 && dj == 0) continue
            if (g.step(ci, cj, di, dj) == WALL) return false
            ci = ni; cj = nj
        }
        return g.lineClear(a, b)
    }

    /** the zones' lattices (the terrain step's), kept in memory */
    val files = RouteWork.Files { map -> Grid.read(ArtHarvest.file("terrain/$map.clb")) }
}
