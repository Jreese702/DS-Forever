package com.jrees.wowcompanion

/**
 * EVERY PLACED BUILDING, FROM THE GAME'S OWN FILES (b-route-data-all; the user on test56: "I need the path building to work without
 * having played there yet"). Each obj ADT's MODF places WMOs (fileDataID, unique id, position, rotation, scale; the interiors' own
 * convention, tools/interiors_native formats.cpp modfPlacements: origin = (ORIGIN - pos.z, ORIGIN - pos.x, pos.y), yaw = rot.y); each WMO
 * root's MOGI lists its groups with their flags and boxes. A building's footprint is its INTERIOR groups (flag 0x2000: the rooms;
 * streets, bridges, fences and a city's open ground are exterior groups) that stand ON the ground there (the floor within
 * [FLOOR_YD] of the terrain, the top above it: a mine's or a cave's rooms under the hills are left out). Pure: bytes in, feet out.
 */
object WorldBuildings {
    const val ORIGIN = 17066.666666666666
    private const val INTERIOR = 0x2000
    private const val EXTERIOR = 0x8

    /** one placement: the WMO root's fileDataID, its unique id, its world origin (x north, y west, z up), its yaw, its scale */
    class Placement(val fid: Int, val uid: Int, val ox: Double, val oy: Double, val oz: Double, val yaw: Double, val scale: Double)

    /** a WMO group's box in the model's own space, whether it is a room, whether it is outdoor (streets, docks, bridges) */
    class Group(val interior: Boolean, val box: FloatArray, val exterior: Boolean = false)

    /** the MODF placements of an obj ADT (fileDataID ones, level; scale from its flags) */
    fun placements(obj: ByteArray): List<Placement> {
        val out = ArrayList<Placement>()
        var o = 0
        while (o + 8 <= obj.size) {
            val n = Db2.le32(obj, o + 4)
            if (n < 0 || o + 8 + n > obj.size) break
            if (tag(obj, o) == "MODF") {
                var at = o + 8
                while (at + 64 <= o + 8 + n) {
                    val fid = Db2.le32(obj, at); val uid = Db2.le32(obj, at + 4)
                    val flags = (obj[at + 56].toInt() and 0xFF) or ((obj[at + 57].toInt() and 0xFF) shl 8)
                    val rawScale = (obj[at + 62].toInt() and 0xFF) or ((obj[at + 63].toInt() and 0xFF) shl 8)
                    fun f(k: Int) = java.lang.Float.intBitsToFloat(Db2.le32(obj, at + k)).toDouble()
                    val px = f(8); val py = f(12); val pz = f(16); val rx = f(20); val ry = f(24); val rz = f(28)
                    if (flags and 8 != 0 && flags and 128 == 0 && listOf(px, py, pz, rx, ry, rz).all { it.isFinite() } &&
                        Math.abs(rx) <= 0.001 && Math.abs(rz) <= 0.001) {
                        val scale = if (flags and 4 != 0) rawScale / 1024.0 else 1.0
                        if (scale > 0) out.add(Placement(fid, uid, ORIGIN - pz, ORIGIN - px, py, Math.toRadians(ry), scale))
                    }
                    at += 64
                }
            }
            o += 8 + n
        }
        return out
    }

    /** a WMO root's groups (MOGI: flags, then the box x1 y1 z1 x2 y2 z2, 32 bytes each) */
    fun groups(root: ByteArray): List<Group> {
        var o = 0
        while (o + 8 <= root.size) {
            val n = Db2.le32(root, o + 4)
            if (n < 0 || o + 8 + n > root.size) return emptyList()
            if (tag(root, o) == "MOGI") return (0 until n / 32).map { g ->
                val b = o + 8 + g * 32
                val fl = Db2.le32(root, b)
                Group(fl and INTERIOR != 0, FloatArray(6) { java.lang.Float.intBitsToFloat(Db2.le32(root, b + 4 + 4 * it)) }, fl and EXTERIOR != 0)
            }
            o += 8 + n
        }
        return emptyList()
    }

    /** a room's floor this near the ground counts as standing on it; its top must rise this far above the ground */
    const val FLOOR_YD = 4.0
    const val TOP_YD = 2.0
    /** rooms smaller than this across are left out (a chimney, a niche); larger than this too (a cave system's hall) */
    const val MIN_YD = 2.0
    const val MAX_YD = 160.0

    /**
     * [p]'s footprint: one Foot per room ([groups]' interior ones) whose top rises above the ground there ([ground] (wx, wy) the
     * terrain's height, NaN = not known: the placement's own height stands in). A city's rooms stand on its raised streets, so the floor
     * is not asked to be near the terrain; a mine's or a cave's rooms under the hills stay below it and are left out.
     */
    fun feet(p: Placement, groups: List<Group>, ground: (Double, Double) -> Double): List<Buildings.Foot> {
        if (isCity(p, groups)) return emptyList()               // a city: its "rooms" span plazas and canals (test56 render)
        val cs = Math.cos(p.yaw); val sn = Math.sin(p.yaw)
        val out = ArrayList<Buildings.Foot>()
        for (g in groups) {
            if (!g.interior) continue
            val b = g.box
            val lx0 = minOf(b[0], b[3]) * p.scale; val lx1 = maxOf(b[0], b[3]) * p.scale
            val ly0 = minOf(b[1], b[4]) * p.scale; val ly1 = maxOf(b[1], b[4]) * p.scale
            val z0 = p.oz + minOf(b[2], b[5]) * p.scale; val z1 = p.oz + maxOf(b[2], b[5]) * p.scale
            val w = maxOf(lx1 - lx0, ly1 - ly0)
            if (w < MIN_YD || w > MAX_YD) continue
            val f = Buildings.Foot(p.ox, p.oy, cs, sn, lx0, lx1, ly0, ly1)
            val h = ground((f.wx0 + f.wx1) / 2, (f.wy0 + f.wy1) / 2).takeIf { !it.isNaN() } ?: p.oz
            if (z1 < h + TOP_YD) continue
            out.add(f)
        }
        return out
    }

    /**
     * [p]'s OUTDOOR groups (MOGI 0x8: a city's streets and squares, a dock, a bridge): walked on, whatever the terrain below them does
     * (Stormwind's ground under its streets reads as steep everywhere). Their footprints, for the slope grid to take as level.
     */
    fun walkways(p: Placement, groups: List<Group>): List<Buildings.Foot> {
        val cs = Math.cos(p.yaw); val sn = Math.sin(p.yaw)
        // a city: ALL its groups are its streets, squares, canals' banks and buildings, walked over the terrain under them
        val city = isCity(p, groups)
        return groups.filter { city || (it.exterior && !it.interior) }.mapNotNull { g ->
            val b = g.box
            val lx0 = minOf(b[0], b[3]) * p.scale; val lx1 = maxOf(b[0], b[3]) * p.scale
            val ly0 = minOf(b[1], b[4]) * p.scale; val ly1 = maxOf(b[1], b[4]) * p.scale
            if (maxOf(lx1 - lx0, ly1 - ly0) < MIN_YD) null else Buildings.Foot(p.ox, p.oy, cs, sn, lx0, lx1, ly0, ly1)
        }
    }

    /** a WMO root's group files (GFID: the groups' fileDataIDs, the first [groups] of them the full-detail ones), empty when none */
    fun groupFiles(root: ByteArray): IntArray {
        var o = 0
        while (o + 8 <= root.size) {
            val n = Db2.le32(root, o + 4)
            if (n < 0 || o + 8 + n > root.size) break
            if (tag(root, o) == "GFID") return IntArray(n / 4) { Db2.le32(root, o + 8 + it * 4) }
            o += 8 + n
        }
        return IntArray(0)
    }

    /**
     * A WALKWAY'S DECK (b-route-climb; Darkshore's Auberdine pier, Mulgore's Stonebull bridge, Ratchet's pier: a walkway's bounding
     * box over the water let routes walk on 21 yd deep sea beside the deck): its outdoor groups' own walkable floor triangles in
     * world 2D, bucketed. [holds] a spot within [DECK_MARGIN_YD] of a triangle.
     */
    class Deck(private val tris: List<DoubleArray>) {
        private val grid = HashMap<Long, ArrayList<Int>>()
        init { for ((k, t) in tris.withIndex()) {
            val x0 = minOf(t[0], t[2], t[4]) - DECK_MARGIN_YD; val x1 = maxOf(t[0], t[2], t[4]) + DECK_MARGIN_YD
            val y0 = minOf(t[1], t[3], t[5]) - DECK_MARGIN_YD; val y1 = maxOf(t[1], t[3], t[5]) + DECK_MARGIN_YD
            for (i in Math.floorDiv(x0.toLong(), 16L)..Math.floorDiv(x1.toLong(), 16L)) for (j in Math.floorDiv(y0.toLong(), 16L)..Math.floorDiv(y1.toLong(), 16L))
                grid.getOrPut(i * 1000003L + j) { ArrayList() }.add(k)
        } }
        val empty get() = tris.isEmpty()
        fun holds(x: Double, y: Double): Boolean = grid[Math.floorDiv(x.toLong(), 16L) * 1000003L + Math.floorDiv(y.toLong(), 16L)]?.any { k ->
            val t = tris[k]; inTri(t, x, y) || segDist(t[0], t[1], t[2], t[3], x, y) <= DECK_MARGIN_YD ||
                segDist(t[2], t[3], t[4], t[5], x, y) <= DECK_MARGIN_YD || segDist(t[4], t[5], t[0], t[1], x, y) <= DECK_MARGIN_YD } == true
        private fun inTri(t: DoubleArray, x: Double, y: Double): Boolean {
            val d0 = (t[2] - t[0]) * (y - t[1]) - (t[3] - t[1]) * (x - t[0]); val d1 = (t[4] - t[2]) * (y - t[3]) - (t[5] - t[3]) * (x - t[2])
            val d2 = (t[0] - t[4]) * (y - t[5]) - (t[1] - t[5]) * (x - t[4])
            return !((d0 < 0 || d1 < 0 || d2 < 0) && (d0 > 0 || d1 > 0 || d2 > 0))
        }
        private fun segDist(ax: Double, ay: Double, bx: Double, by: Double, x: Double, y: Double): Double {
            val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
            val u = if (l2 <= 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0.0, 1.0)
            return Math.hypot(ax + u * dx - x, ay + u * dy - y)
        }
    }
    const val DECK_MARGIN_YD = 1.0

    /**
     * placement [p]'s deck: the floor triangles of its outdoor groups ([walkways]' groups), read from its group files ([root]'s GFID,
     * [read] a file by its id); null when the groups can't be read (the caller keeps the bounding boxes then)
     */
    fun deck(p: Placement, root: ByteArray, read: (Int) -> ByteArray?): Deck? {
        val gs = groups(root); val files = groupFiles(root)
        if (gs.isEmpty() || files.size < gs.size) return null
        val city = isCity(p, gs)
        val cs = Math.cos(p.yaw); val sn = Math.sin(p.yaw)
        val tris = ArrayList<DoubleArray>()
        for ((k, g) in gs.withIndex()) {
            if (!(city || (g.exterior && !g.interior))) continue
            val bytes = files[k].takeIf { it != 0 }?.let(read) ?: return null
            val t = Caves.floorTris(bytes)
            for (q in 0 until t.size / 9) tris.add(DoubleArray(6) { c -> val v = q * 9 + (c / 2) * 3
                val lx = t[v] * p.scale; val ly = t[v + 1] * p.scale
                if (c % 2 == 0) p.ox - lx * cs + ly * sn else p.oy - lx * sn - ly * cs })
        }
        return Deck(tris)
    }

    /** wider than this (yd), a WMO is a city or a stretch of terrain (Stormwind, Ironforge's mountain): no rooms as walls; level */
    const val CITY_YD = 300.0
    fun isCity(p: Placement, groups: List<Group>): Boolean {
        if (groups.isEmpty()) return false
        val x0 = groups.minOf { minOf(it.box[0], it.box[3]) }; val x1 = groups.maxOf { maxOf(it.box[0], it.box[3]) }
        val y0 = groups.minOf { minOf(it.box[1], it.box[4]) }; val y1 = groups.maxOf { maxOf(it.box[1], it.box[4]) }
        return maxOf(x1 - x0, y1 - y0) * p.scale > CITY_YD
    }

    private fun tag(b: ByteArray, o: Int) = String(charArrayOf(b[o + 3].toInt().toChar(), b[o + 2].toInt().toChar(), b[o + 1].toInt().toChar(), b[o].toInt().toChar()))
}
