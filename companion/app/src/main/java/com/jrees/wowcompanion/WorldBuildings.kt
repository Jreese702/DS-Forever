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
    class Placement(val fid: Int, val uid: Int, val ox: Double, val oy: Double, val oz: Double, val yaw: Double, val scale: Double,
                    val rx: Double = 0.0, val rz: Double = 0.0) {
        /**
         * the model's point (lx, ly, lz) in world yards. Level placements: `wx = ox - lx·cos(yaw) + ly·sin(yaw)`,
         * `wy = oy - lx·sin(yaw) - ly·cos(yaw)`, `wz = oz + lz` ([Buildings.Foot]). A tilted one ([placements] with `tilted`: MODF's
         * rotation x and z, radians) is turned in the ADT's own frame (y up) by `Ry(yaw - 90°)·Rz(-rx)·Rx(rz)` on the model's (x, z, -y),
         * which is the level formula when both tilts are 0. Checked on the data: Stonetalon's Talondeep Path tunnel (430 yd long,
         * tilted 3°) meets the terrain at both ends only so; level, its far end floats 17 yd over the ground.
         */
        fun world(lx: Double, ly: Double, lz: Double): DoubleArray {
            val x = lx * scale; val y = ly * scale; val z = lz * scale
            val cs = Math.cos(yaw); val sn = Math.sin(yaw)
            if (rx == 0.0 && rz == 0.0) return doubleArrayOf(ox - x * cs + y * sn, oy - x * sn - y * cs, oz + z)
            // m = (x, z, -y) (the model's y-up frame); Rx(rz), then Rz(-rx), then Ry(yaw - 90°)
            val cz = Math.cos(rz); val sz = Math.sin(rz)
            val m0 = x; val m1 = z * cz + y * sz; val m2 = z * sz - y * cz
            val cx = Math.cos(-rx); val sx = Math.sin(-rx)
            val n0 = m0 * cx - m1 * sx; val n1 = m0 * sx + m1 * cx; val n2 = m2
            // Ry(yaw - 90°): cos = sin(yaw), sin = -cos(yaw)
            val a0 = n0 * sn - n2 * cs; val a2 = n0 * cs + n2 * sn
            return doubleArrayOf(ox - a2, oy - a0, oz + n1)
        }
    }

    /** a WMO group's box in the model's own space, whether it is a room, whether it is outdoor (streets, docks, bridges) */
    class Group(val interior: Boolean, val box: FloatArray, val exterior: Boolean = false)

    /** the MODF placements of an obj ADT (fileDataID ones, level, or also tilted ones when [tilted]: only callers that place points
     *  by [Placement.world] ask for them, as caves do; scale from its flags) */
    fun placements(obj: ByteArray, tilted: Boolean = false): List<Placement> {
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
                        (tilted || Math.abs(rx) <= 0.001 && Math.abs(rz) <= 0.001)) {
                        val scale = if (flags and 4 != 0) rawScale / 1024.0 else 1.0
                        val level = Math.abs(rx) <= 0.001 && Math.abs(rz) <= 0.001
                        if (scale > 0) out.add(Placement(fid, uid, ORIGIN - pz, ORIGIN - px, py, Math.toRadians(ry), scale,
                            if (level) 0.0 else Math.toRadians(rx), if (level) 0.0 else Math.toRadians(rz)))
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
     * AN OUTDOOR-ONLY BUILDING (the user on Zephras, test12: "it pathed me directly into a building that didn't have a back door"):
     * WoW Forever's new buildings (Valanaar's houses) are one OUTDOOR group each, no interior one, so [feet] sees no room and [walkways]
     * leveled them as a street. [p] is one when all its groups are outdoor (none interior, not a city), it stands on the ground (its
     * base no more than [STAND_BASE_YD] under the terrain at its middle, nor [STAND_LIFT_YD] over it: no buried rock mass under a town,
     * no deck in the air), rises [STAND_TALL_YD] or more over it, and is compact (no side over [STAND_MAX_YD], the long side no more than
     * [STAND_RATIO] the short: a bridge, a wall, a causeway are long and thin). Its whole box, or null. A guess from the box alone - the
     * device's 25 yd grid takes it as a COST (Buildings.PART_COVER at most), never a wall; the lattice's floors say what is walked.
     */
    fun standing(p: Placement, groups: List<Group>, ground: (Double, Double) -> Double): Buildings.Foot? {
        if (groups.isEmpty() || groups.any { it.interior } || groups.none { it.exterior } || isCity(p, groups)) return null
        val lx0 = groups.minOf { minOf(it.box[0], it.box[3]) } * p.scale; val lx1 = groups.maxOf { maxOf(it.box[0], it.box[3]) } * p.scale
        val ly0 = groups.minOf { minOf(it.box[1], it.box[4]) } * p.scale; val ly1 = groups.maxOf { maxOf(it.box[1], it.box[4]) } * p.scale
        val z0 = p.oz + groups.minOf { minOf(it.box[2], it.box[5]) } * p.scale; val z1 = p.oz + groups.maxOf { maxOf(it.box[2], it.box[5]) } * p.scale
        val a = lx1 - lx0; val b = ly1 - ly0
        if (maxOf(a, b) > STAND_MAX_YD || minOf(a, b) < MIN_YD || maxOf(a, b) > STAND_RATIO * minOf(a, b)) return null
        val f = Buildings.Foot(p.ox, p.oy, Math.cos(p.yaw), Math.sin(p.yaw), lx0, lx1, ly0, ly1)
        val h = ground((f.wx0 + f.wx1) / 2, (f.wy0 + f.wy1) / 2); if (h.isNaN()) return null
        return f.takeIf { z0 >= h - STAND_BASE_YD && z0 <= h + STAND_LIFT_YD && z1 >= h + STAND_TALL_YD }
    }
    const val STAND_MAX_YD = 90.0
    const val STAND_RATIO = 3.0
    const val STAND_TALL_YD = 10.0
    const val STAND_BASE_YD = 8.0
    const val STAND_LIFT_YD = 3.0

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
    class Deck(private val tris: List<DoubleArray>, private val zs: List<DoubleArray>? = null) {      // zs: each triangle's corners' world heights
        private val grid = HashMap<Long, ArrayList<Int>>()
        init { for ((k, t) in tris.withIndex()) {
            val x0 = minOf(t[0], t[2], t[4]) - DECK_MARGIN_YD; val x1 = maxOf(t[0], t[2], t[4]) + DECK_MARGIN_YD
            val y0 = minOf(t[1], t[3], t[5]) - DECK_MARGIN_YD; val y1 = maxOf(t[1], t[3], t[5]) + DECK_MARGIN_YD
            for (i in Math.floorDiv(x0.toLong(), 16L)..Math.floorDiv(x1.toLong(), 16L)) for (j in Math.floorDiv(y0.toLong(), 16L)..Math.floorDiv(y1.toLong(), 16L))
                grid.getOrPut(i * 1000003L + j) { ArrayList() }.add(k)
        } }
        val empty get() = tris.isEmpty()
        /** its triangles in world 2D (x0, y0, x1, y1, x2, y2): the harness's pictures and checks */
        val triangles: List<DoubleArray> get() = tris
        /** each triangle's corners' world heights (null: a box's footprint, no floor read) */
        val heights: List<DoubleArray>? get() = zs
        fun holds(x: Double, y: Double): Boolean = grid[Math.floorDiv(x.toLong(), 16L) * 1000003L + Math.floorDiv(y.toLong(), 16L)]?.any { k ->
            val t = tris[k]; inTri(t, x, y) || segDist(t[0], t[1], t[2], t[3], x, y) <= DECK_MARGIN_YD ||
                segDist(t[2], t[3], t[4], t[5], x, y) <= DECK_MARGIN_YD || segDist(t[4], t[5], t[0], t[1], x, y) <= DECK_MARGIN_YD } == true
        /**
         * the deck's floor height at world ([x], [y]): of its triangles within [DECK_MARGIN_YD] there, the LOWEST floor at or above
         * [above] (the water's surface or the ground under it: a covered bridge's roof and a pier's buildings stand over its deck, its
         * pillars' feet under the water), on the triangle's own plane at the nearest point of it; NaN off the deck or with no heights.
         * [orBuried]: with no floor that high, the highest one under it (a tunnel's floor under its hill: Wetlands' Dun Algaz, whose
         * outdoor floor 40 yd under a 60-75 degree hillside leveled it, a route climbing it)
         */
        fun heightAt(x: Double, y: Double, above: Double = Double.NEGATIVE_INFINITY, orBuried: Boolean = false): Double {
            val z = zs ?: return Double.NaN
            var best = Double.NaN; var under = Double.NaN
            for (k in grid[Math.floorDiv(x.toLong(), 16L) * 1000003L + Math.floorDiv(y.toLong(), 16L)] ?: return Double.NaN) {
                val t = tris[k]
                val inside = inTri(t, x, y)
                if (!inside && segDist(t[0], t[1], t[2], t[3], x, y) > DECK_MARGIN_YD && segDist(t[2], t[3], t[4], t[5], x, y) > DECK_MARGIN_YD &&
                    segDist(t[4], t[5], t[0], t[1], x, y) > DECK_MARGIN_YD) continue
                // barycentric on the plane, clamped onto the triangle (a spot in the margin takes its edge's height)
                val det = (t[3] - t[5]) * (t[0] - t[4]) + (t[4] - t[2]) * (t[1] - t[5])
                val h = if (Math.abs(det) < 1e-9) z[k].average() else {
                    var a = ((t[3] - t[5]) * (x - t[4]) + (t[4] - t[2]) * (y - t[5])) / det
                    var b = ((t[5] - t[1]) * (x - t[4]) + (t[0] - t[4]) * (y - t[5])) / det
                    a = a.coerceIn(0.0, 1.0); b = b.coerceIn(0.0, 1.0 - a)
                    a * z[k][0] + b * z[k][1] + (1 - a - b) * z[k][2]
                }
                if (h >= above && (best.isNaN() || h < best)) best = h
                if (h < above && (under.isNaN() || h > under)) under = h
            }
            return if (best.isNaN() && orBuried) under else best
        }
        /** whether the deck at world ([x], [y]) stands over a liquid whose surface is [surface] (NaN: dry, any): a floor at or over it, or
         *  no heights read (a box) - a board sunk under the water is no bridge over it (the review's M2) */
        fun over(x: Double, y: Double, surface: Float): Boolean {
            if (surface.isNaN() || zs == null) return true
            val hs = heightsAt(x, y); return hs.isEmpty() || hs.any { it >= surface - 0.5 }
        }
        /** every floor's height at world ([x], [y]) (its triangles within [DECK_MARGIN_YD] there, as [heightAt]); empty off the deck */
        fun heightsAt(x: Double, y: Double): DoubleArray {
            val z = zs ?: return DoubleArray(0)
            val out = ArrayList<Double>()
            for (k in grid[Math.floorDiv(x.toLong(), 16L) * 1000003L + Math.floorDiv(y.toLong(), 16L)] ?: return DoubleArray(0)) {
                val t = tris[k]
                if (!inTri(t, x, y) && segDist(t[0], t[1], t[2], t[3], x, y) > DECK_MARGIN_YD && segDist(t[2], t[3], t[4], t[5], x, y) > DECK_MARGIN_YD &&
                    segDist(t[4], t[5], t[0], t[1], x, y) > DECK_MARGIN_YD) continue
                val det = (t[3] - t[5]) * (t[0] - t[4]) + (t[4] - t[2]) * (t[1] - t[5])
                out.add(if (Math.abs(det) < 1e-9) z[k].average() else {
                    var a = ((t[3] - t[5]) * (x - t[4]) + (t[4] - t[2]) * (y - t[5])) / det
                    var b = ((t[5] - t[1]) * (x - t[4]) + (t[0] - t[4]) * (y - t[5])) / det
                    a = a.coerceIn(0.0, 1.0); b = b.coerceIn(0.0, 1.0 - a)
                    a * z[k][0] + b * z[k][1] + (1 - a - b) * z[k][2]
                })
            }
            return out.toDoubleArray()
        }
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
        val tris = ArrayList<DoubleArray>(); val zs = ArrayList<DoubleArray>()
        for ((k, g) in gs.withIndex()) {
            if (!(city || (g.exterior && !g.interior))) continue
            val bytes = files[k].takeIf { it != 0 }?.let(read) ?: return null
            placeTris(p, Caves.floorTris(bytes), tris, zs)
        }
        return Deck(tris, zs)
    }

    /** model-frame triangles [t] (9 floats each) of placement [p] into world 2D triangles [tris] and their corners' heights [zs] */
    private fun placeTris(p: Placement, t: FloatArray, tris: MutableList<DoubleArray>, zs: MutableList<DoubleArray>) {
        // by Placement.world: a tilted walkway too (Arathi's Thandol Span, 3 degrees: left out, the river cut the road off)
        for (q in 0 until t.size / 9) {
            val w = Array(3) { c -> val v = q * 9 + c * 3; p.world(t[v].toDouble(), t[v + 1].toDouble(), t[v + 2].toDouble()) }
            tris.add(DoubleArray(6) { c -> w[c / 2][c % 2] })
            zs.add(DoubleArray(3) { c -> w[c][2] })
        }
    }

    // ---------------------------------------------------------------- M2 bridges (doodads)

    /**
     * the MDDF doodad placements of an obj ADT (M2 models: 36 bytes each - fileDataID when flags & 0x40, unique id, position, rotation,
     * scale / 1024, flags), in the same frame as [placements]. A doodad tilted more than [M2_TILT_DEG] is left out (a bridge lies level).
     */
    fun doodads(obj: ByteArray): List<Placement> {
        val out = ArrayList<Placement>()
        var o = 0
        while (o + 8 <= obj.size) {
            val n = Db2.le32(obj, o + 4)
            if (n < 0 || o + 8 + n > obj.size) break
            if (tag(obj, o) == "MDDF") {
                var at = o + 8
                while (at + 36 <= o + 8 + n) {
                    val fid = Db2.le32(obj, at); val uid = Db2.le32(obj, at + 4)
                    fun f(k: Int) = java.lang.Float.intBitsToFloat(Db2.le32(obj, at + k)).toDouble()
                    val px = f(8); val py = f(12); val pz = f(16); val rx = f(20); val ry = f(24); val rz = f(28)
                    val scale = ((obj[at + 32].toInt() and 0xFF) or ((obj[at + 33].toInt() and 0xFF) shl 8)) / 1024.0
                    val flags = (obj[at + 34].toInt() and 0xFF) or ((obj[at + 35].toInt() and 0xFF) shl 8)
                    if (flags and 0x40 != 0 && fid != 0 && scale > 0 && listOf(px, py, pz, rx, ry, rz).all { it.isFinite() } &&
                        Math.abs(rx) <= M2_TILT_DEG && Math.abs(rz) <= M2_TILT_DEG)
                        out.add(Placement(fid, uid, ORIGIN - pz, ORIGIN - px, py, Math.toRadians(ry), scale))
                    at += 36
                }
            }
            o += 8 + n
        }
        return out
    }
    const val M2_TILT_DEG = 5.0

    /** an M2 file's MD20 block (inside its MD21 chunk; or the whole file, an old unchunked one): its offset, or -1 */
    private fun md20(m2: ByteArray): Int {
        if (m2.size >= 8 && tag(m2, 0) == "MD20".reversed()) return 0
        var o = 0
        while (o + 8 <= m2.size) {
            val n = Db2.le32(m2, o + 4)
            if (n < 0 || o + 8 + n > m2.size) return -1
            if (String(CharArray(4) { m2[o + it].toInt().toChar() }) == "MD21") return o + 8
            o += 8 + n
        }
        return -1
    }

    /** an M2's own model name (its MD20 header's name: "DuskwoodCoveredBridge"; the phone has no listfile), "" when unreadable */
    fun m2Name(m2: ByteArray): String {
        val b = md20(m2); if (b < 0 || b + 16 > m2.size) return ""
        val n = Db2.le32(m2, b + 8); val at = Db2.le32(m2, b + 12)
        if (n <= 0 || n > 256 || at < 0 || b + at + n > m2.size) return ""
        return String(CharArray(n) { m2[b + at + it].toInt().toChar() }).trimEnd('\u0000')
    }

    /** the model names that mark a walkable bridge (b-safepath-water: Duskwood's covered bridge into Westfall is an M2), lower case */
    val M2_BRIDGES = listOf("bridge", "dock", "pier", "walkway")
    fun isM2Bridge(name: String): Boolean = name.lowercase().let { n -> M2_BRIDGES.any { it in n } }

    /**
     * an M2's walkable collision triangles in its own frame (9 floats each, as Caves.floorTris): its MD20 collision mesh
     * (collisionIndices at 0xD8, collisionPositions at 0xE0), the faces no steeper than Caves.FLOOR_DEG; empty when it has none
     */
    fun m2FloorTris(m2: ByteArray): FloatArray {
        val b = md20(m2); if (b < 0 || b + 0xE8 > m2.size) return FloatArray(0)
        val ni = Db2.le32(m2, b + 0xD8); val oi = Db2.le32(m2, b + 0xDC); val nv = Db2.le32(m2, b + 0xE0); val ov = Db2.le32(m2, b + 0xE4)
        if (ni <= 0 || nv <= 0 || oi < 0 || ov < 0 || b + oi + ni * 2 > m2.size || b + ov + nv * 12 > m2.size) return FloatArray(0)
        fun v(k: Int, c: Int) = java.lang.Float.intBitsToFloat(Db2.le32(m2, b + ov + k * 12 + c * 4))
        val out = ArrayList<Float>()
        val cosMax = Math.cos(Math.toRadians(Caves.FLOOR_DEG))
        for (t in 0 until ni / 3) {
            val idx = IntArray(3) { q -> (m2[b + oi + (t * 3 + q) * 2].toInt() and 0xFF) or ((m2[b + oi + (t * 3 + q) * 2 + 1].toInt() and 0xFF) shl 8) }
            if (idx.any { it >= nv }) continue
            val ux = v(idx[1], 0) - v(idx[0], 0); val uy = v(idx[1], 1) - v(idx[0], 1); val uz = v(idx[1], 2) - v(idx[0], 2)
            val wx = v(idx[2], 0) - v(idx[0], 0); val wy = v(idx[2], 1) - v(idx[0], 1); val wz = v(idx[2], 2) - v(idx[0], 2)
            val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
            val len = Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble())
            if (len <= 0.0 || Math.abs(nz) / len < cosMax) continue
            for (q in 0..2) for (c in 0..2) out.add(v(idx[q], c))
        }
        return out.toFloatArray()
    }

    /**
     * AN M2 BRIDGE'S DECK (b-safepath-water; the user: "Pathing shouldn't be in water" - so a river is crossed only where a bridge is,
     * and Duskwood's covered bridge into Westfall is a doodad, not a WMO): placement [p] of model [m2], when its own model name says
     * bridge, dock, pier or walkway ([isM2Bridge]) - its collision mesh's walkable triangles in world 2D, as a WMO walkway's [deck].
     * Null when the model is no bridge or has no collision floor.
     */
    fun m2Deck(p: Placement, m2: ByteArray): Deck? = m2BridgeFloor(m2)?.let { m2DeckOf(p, it) }
    /**
     * model [m2]'s bridge floor: its [m2FloorTris] when its own name says bridge ([isM2Bridge]) and the floor spans at least
     * [M2_MIN_SPAN_YD] (a brick or a pylon's top named for the bridge it decorates - RedRidgeBridgeBrick01, 3 yd - is no deck); else null
     */
    fun m2BridgeFloor(m2: ByteArray): FloatArray? {
        val name = m2Name(m2)
        // ... or a model with no name of its own (WoW Forever's new piers: Tanaris' Steamwheedle Port, 7539395 / 7539398) whose floor is
        // one flat board: rising no more than [M2_FLAT_RISE_YD] over its span (a rock or a tree always has its name)
        if (!isM2Bridge(name) && name.isNotBlank()) return null
        val t = m2FloorTris(m2); if (t.isEmpty()) return null
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (v in 0 until t.size / 3) { x0 = minOf(x0, t[v * 3]); x1 = maxOf(x1, t[v * 3]); y0 = minOf(y0, t[v * 3 + 1]); y1 = maxOf(y1, t[v * 3 + 1])
            z0 = minOf(z0, t[v * 3 + 2]); z1 = maxOf(z1, t[v * 3 + 2]) }
        if (name.isBlank() && z1 - z0 > M2_FLAT_RISE_YD) return null
        return t.takeIf { maxOf(x1 - x0, y1 - y0) >= M2_MIN_SPAN_YD }
    }
    const val M2_FLAT_RISE_YD = 2.0
    const val M2_MIN_SPAN_YD = 8.0
    /** [p]'s deck from its model's own floor triangles ([m2FloorTris]: cached per model by the callers); null when there are none */
    fun m2DeckOf(p: Placement, t: FloatArray): Deck? {
        if (t.isEmpty()) return null
        val tris = ArrayList<DoubleArray>(); val zs = ArrayList<DoubleArray>()
        placeTris(p, t, tris, zs)
        return Deck(tris, zs)
    }

    /**
     * the M2 bridges of obj ADT [obj]: each doodad standing [near] water (the rest are never read: trees, rocks), its model's floor
     * ([floorOf] a model's fileDataID -> its [m2FloorTris] when [isM2Bridge] by its own name, else null; the callers cache it per model)
     */
    fun m2Decks(obj: ByteArray, near: (Double, Double) -> Boolean, floorOf: (Int) -> FloatArray?): List<Deck> =
        doodads(obj).filter { near(it.ox, it.oy) }.mapNotNull { p -> floorOf(p.fid)?.let { m2DeckOf(p, it) } }
    /** a doodad this near the water is read as a possible bridge (a bridge's middle stands over its river) */
    const val M2_NEAR_WATER_YD = 24.0

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
