package com.jrees.wowcompanion

/**
 * BUILDINGS AS WALLS ON THE ROUTE'S GRID (b-route-buildings; the user on test54: "Sometimes checkpoints are going through buildings").
 * The interiors' placed buildings (their placements and storeys, built on the device from the user's own game files) give each one's
 * footprint: the union of its storeys' outlines in the model's own space (lx / ly), placed by its l2w ([ox], [oy], [cs], [sn]: MapView's
 * own convention). A 25 yd cell the buildings cover for the most part is a wall; one partly covered costs more. A building's doors are
 * not modelled at 25 yd: the walls do not hold near the route's start and goal (DangerGrid.WALL_FREE_YD). Pure.
 */
object Buildings {
    /** one placed building: its placement and its footprint's local bounds; [wx0]..[wx1], [wy0]..[wy1] its world box */
    class Foot(val ox: Double, val oy: Double, val cs: Double, val sn: Double,
               val lx0: Double, val lx1: Double, val ly0: Double, val ly1: Double) {
        val wx0: Double; val wx1: Double; val wy0: Double; val wy1: Double
        init {
            val xs = DoubleArray(4); val ys = DoubleArray(4)
            var i = 0
            for (lx in doubleArrayOf(lx0, lx1)) for (ly in doubleArrayOf(ly0, ly1)) { xs[i] = worldX(lx, ly); ys[i] = worldY(lx, ly); i++ }
            wx0 = xs.min(); wx1 = xs.max(); wy0 = ys.min(); wy1 = ys.max()
        }
        fun worldX(lx: Double, ly: Double) = ox - lx * cs + ly * sn
        fun worldY(lx: Double, ly: Double) = oy - lx * sn - ly * cs
        /** world ([wx], [wy]) inside the footprint */
        fun holds(wx: Double, wy: Double): Boolean {
            if (wx < wx0 || wx > wx1 || wy < wy0 || wy > wy1) return false
            val lx = -(cs * (wx - ox) + sn * (wy - oy)); val ly = sn * (wx - ox) - cs * (wy - oy)
            return lx in lx0..lx1 && ly in ly0..ly1
        }
    }

    /** a cell this much covered is a wall; this much, dearer by [PART_COST] */
    const val WALL_COVER = 0.55f
    const val PART_COVER = 0.2f
    const val PART_COST = 4f

    /** the feet whose world box meets the map's rectangle [r] */
    fun onMap(feet: List<Foot>, r: PoiStore.Rect): List<Foot> =
        feet.filter { it.wx1 >= r.bottom && it.wx0 <= r.top && it.wy1 >= r.right && it.wy0 <= r.left }

    /**
     * how much of the cell centred on the map's ([x], [y]) ([cellX] x [cellY] of the map's 0..1) the [feet] cover, 0..1, from 3 x 3
     * samples (world: wy = r.left - x * r.width, wx = r.top - y * r.height)
     */
    fun cover(feet: List<Foot>, x: Double, y: Double, cellX: Double, cellY: Double, r: PoiStore.Rect): Float {
        if (feet.isEmpty()) return 0f
        val wy = r.left - x * r.width; val wx = r.top - y * r.height
        return coverWorld(feet, wx, wy, cellY * r.height / 2, cellX * r.width / 2)
    }

    /** the same at world ([wx], [wy]), the cell [hx] yards north-south and [hy] east-west each side */
    fun coverWorld(feet: List<Foot>, wx: Double, wy: Double, hx: Double, hy: Double): Float {
        val near = feet.filter { it.wx1 >= wx - hx && it.wx0 <= wx + hx && it.wy1 >= wy - hy && it.wy0 <= wy + hy }
        if (near.isEmpty()) return 0f
        var n = 0
        for (i in -1..1) for (j in -1..1) {
            val sx = wx + i * hx * 2 / 3; val sy = wy + j * hy * 2 / 3
            if (near.any { it.holds(sx, sy) }) n++
        }
        return n / 9f
    }
}
