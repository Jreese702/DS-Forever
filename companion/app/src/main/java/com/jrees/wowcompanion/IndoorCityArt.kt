package com.jrees.wowcompanion

import kotlin.math.abs

/**
 * THE CAPITALS' INDOOR ART BY POSITION (the user, 2026-11-23, on test 1523: "I cant see iron forges map at all when walking around in it";
 * the pick D2, "Game's art now, slice later": "Draw the game's own indoor minimap art across Ironforge, Undercity, Stormwind and
 * Orgrimmar ..., with the city's painted map wherever there's none").
 *
 * Our builder maps the capitals' shops and side rooms only (the city rule caps a room at 120 x 80 yd; Ironforge's halls are 86 - 153 yd
 * tall caverns, about 58 % of the city), and the game's own WMOMinimapTexture art (103 of Ironforge's 104 groups) was drawn only INSIDE
 * one of our built rooms ([MapView.levelArtOf]). Now, inside a capital's footprint, the groups whose LOCAL box holds the player are
 * found by the root's one placement (the shared transform every city room uses) and drawn with the game's tiles, our plans on top.
 * Which floor ([Tracker]): the height the storey tracker knows (a built room's floor carries over as the player walks out into a hall),
 * else the floor walked so far, else the city's usual floor, drawn as unsure while another floor is possible - the storey tracker's own
 * honesty ([MapView.track]: a guess is drawn at 55 %).
 *
 * WHEN: inside a capital's footprint AND indoors (the add-on's `indoors`, IsIndoors). Whether Ironforge's streets say indoors is the one
 * unknown the device read settles (the plan's section 5): a city whose streets say outdoors gets [City.positionOnly] = true on its own
 * line below, and its art then shows by position alone.
 *
 * THE DEVICE READ (test 1524, 2026-11-24, on Ironforge's ring: "indoors": false, mapID 1455) and the user's pick "Ironforge + Undercity"
 * ("Both are fully enclosed cities you can mount in, so the game probably reports 'outdoors' in both. Stormwind and Orgrimmar keep
 * needing 'indoors', since their streets really are outside."): those two are [City.positionOnly]. By position means inside the city's
 * footprint AND on the city's own map (the add-on's mapID, the game's own word for where the player is: [byPosition]), never on a taxi:
 * out of Ironforge's gates the game says Dun Morogh, and on the Ruins of Lordaeron - ABOVE the Undercity, inside its footprint - it says
 * Tirisfal Glades, so neither gets the city's art. There MapView is indoors for everything ([indoorsHere]): the built rooms, the game's
 * art, the dark in the city's own shape, the note and the indoor widths.
 *
 * PART OF THE WORLD AROUND IT (1526; the user on test 1525, 2026-11-24: "Why can I see the actual map background in iron forge? ... it's
 * weird and I hate it" - the painted city map is gone - and then: "I want it to look like its still part of the world around it. Will it
 * show black when I zoom out or the mountain? Can't we just make it seemless like with stormwind?"): the outdoor map draws as everywhere
 * else, around and over the city, at every zoom; the game's indoor art is drawn on it, in place, in the city's own shape; the gaps inside
 * that shape no art covers are [BACKING] - the indoor scrim's own dark - under the floor's groups' own turned boxes only, never a
 * rectangle over the footprint. Zoomed out past [TILES_MAX] tiles the floor is one picture ([Mosaic]): the art never drops out. The note
 * says "No indoor map here yet" only where the player stands on neither the game's art nor a plan of ours ([Pick.own] < 0).
 *
 * Pure (the tests): MapView reads the art index (wmominimap.json) and draws.
 */
internal object IndoorCityArt {
    /**
     * One capital. [l2w]: its root's placement as MapView's Place reads it (ox, oy, cos, sin; the PC host bake 2026-10-13, the same for
     * every one of its rooms; a built room of the root on this continent wins over it). [rect]: its footprint, the world rect (top,
     * bottom, left, right) of the city's own map (the Map room's worldmap/<uiMap>/index.json, the same numbers)
     */
    class City(val name: String, val root: Int, val uiMap: Int, val continent: Int, val l2w: DoubleArray, val rect: DoubleArray,
               val positionOnly: Boolean = false) {
        fun localX(wx: Double, wy: Double, t: DoubleArray = l2w) = -(t[2] * (wx - t[0]) + t[3] * (wy - t[1]))
        fun localY(wx: Double, wy: Double, t: DoubleArray = l2w) = t[3] * (wx - t[0]) - t[2] * (wy - t[1])
    }

    val CAPITALS: List<City> = listOf(
        City("Stormwind", 7962574, 1453, 0, doubleArrayOf(-8931.634114583332, 539.2897135416679, 0.7826081568524139, 0.6225146366376196),
            doubleArrayOf(-7995.8301, -9154.1699, 1722.92, -14.584), positionOnly = false),
        City("Ironforge", 7801267, 1455, 0, doubleArrayOf(-5008.526692708332, -849.4290364583321, 0.6360782202777643, -0.7716245833877197),
            doubleArrayOf(-4569.2412, -5096.8457, -713.5914, -1504.2164), positionOnly = true),
        City("Undercity", 7675285, 1458, 0, doubleArrayOf(1647.7174479166679, 239.83072916666788, -0.9999619230641713, 0.008726535498374403),
            doubleArrayOf(1877.9453, 1237.8412, 873.1926, -86.1824), positionOnly = true),
        City("Orgrimmar", 7964229, 1454, 1, doubleArrayOf(1500.5026041666679, -4418.192708333332, 0.8290375725550417, 0.5591929034707469),
            doubleArrayOf(2273.8772, 1338.4606, -3680.6011, -5083.2056), positionOnly = false),
    )

    fun byRoot(root: Int): City? = CAPITALS.firstOrNull { it.root == root }

    /** the indoor scrim's colour (MapView draws it at 0xA0 over the outdoor map under a built room's plan) */
    const val DARK_RGB = 0x100C08
    /** 1526: the city's own shape under its art (the floor's groups' turned boxes) - the indoor scrim's own dark, opaque */
    const val BACKING = (0xFF shl 24) or DARK_RGB

    /**
     * more tiles than this in view: decoded at half size whatever the zoom (memory: MapView keeps 24 MB of pictures in all, a built
     * room's storeys up to ~3 MB each among them; 24 full 256 px tiles are 6 MB)
     */
    const val HALF_ABOVE = 24

    /**
     * a tile is decoded at half size (review of 2026-11-23: with more than 12 in view they were, and the open map at ~5 px a yard drew
     * them upscaled about 5 x, a blur) only when the view has at most a pixel a yard ([pxPerYd]; a tile has [WmoMinimap.PX_PER_YD], so
     * half size loses nothing there) or more than [HALF_ABOVE] tiles are in view (256 KB a full-size 256 px tile)
     */
    fun halfSize(tiles: Int, pxPerYd: Double): Boolean = tiles > HALF_ABOVE || pxPerYd <= WmoMinimap.PX_PER_YD / 2

    /** more of the game's tiles than this in view (a whole city at once, the open map zoomed out): the floor's [Mosaic] instead */
    const val TILES_MAX = 64

    /**
     * ONE PICTURE OF THE FLOOR, ZOOMED OUT (1526 follow-up: with no painted map under it, the open map zoomed out past [TILES_MAX] tiles
     * drew nothing over the dark - a dark block where the city is). The picked groups' tiles laid into one bitmap in the root's own space
     * (its top row the high-y side, as a tile's), built off the UI thread from tiles decoded small, kept in MapView's plan cache (its
     * 24 MB budget) and drawn through the same placement as the tiles. Its resolution is a level of detail: the view's own pixels a yard
     * rounded up to a power of two, at most [MAX_PX_PER_YD] (a half-size tile's own: what the half-size path draws there); its size is
     * bounded ([MAX_SIDE] a side, [MAX_BYTES] in all; Ironforge's or the Undercity's whole floor is about 2 MB at the most). Pure.
     */
    object Mosaic {
        /** a half-size tile's own pixels a yard (WmoMinimap.PX_PER_YD / 2) */
        const val MAX_PX_PER_YD = 1.0
        /** the coarsest level (a continent in view: the city is a few pixels) */
        const val MIN_PX_PER_YD = 0.125
        const val MAX_SIDE = 2048
        const val MAX_BYTES = 4L * 1024 * 1024

        /** the level for a view of [viewPxPerYd]: the next power of two up from it, [MIN_PX_PER_YD] .. [MAX_PX_PER_YD] */
        fun lod(viewPxPerYd: Double): Double {
            var l = MAX_PX_PER_YD
            while (l / 2 >= viewPxPerYd && l / 2 >= MIN_PX_PER_YD) l /= 2
            return l
        }

        /** one tile's place: its texture [fid] and its rect in the mosaic's pixels (left, top, right, bottom) */
        class Cell(val fid: Int, val l: Float, val t: Float, val r: Float, val b: Float)

        /**
         * the mosaic: its local rect ([x0]..[x1], [y0]..[y1], yards; the top row is [y1]), [pxPerYd] (the level, or less to stay in
         * bounds), its pixels [w] x [h], [sample] (BitmapFactory's inSampleSize for its tiles) and [cells] (the groups in index order)
         */
        class Layout(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val pxPerYd: Double, val w: Int, val h: Int,
                     val sample: Int, val cells: List<Cell>) {
            val bytes: Long get() = w.toLong() * h * 4
        }

        /** the mosaic of [pick] (indices into [groups]) at level [lod], or null (no tiles) */
        fun layout(groups: List<WmoMinimap.Group>, pick: List<Int>, lod: Double): Layout? {
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            val tiles = ArrayList<Pair<Int, DoubleArray>>()
            for (gi in pick.toSortedSet()) {
                val g = groups.getOrNull(gi) ?: continue
                for (t in g.tiles) {
                    val q = WmoMinimap.tileRect(g.bb, t[0], t[1], t[3], t[4])
                    if (!(q[2] > q[0] && q[3] > q[1])) continue
                    tiles.add(t[2] to q)
                    x0 = minOf(x0, q[0]); y0 = minOf(y0, q[1]); x1 = maxOf(x1, q[2]); y1 = maxOf(y1, q[3])
                }
            }
            if (tiles.isEmpty()) return null
            val dx = x1 - x0; val dy = y1 - y0
            // the level, or less: never more than MAX_SIDE a side nor MAX_BYTES in all
            var px = minOf(lod.coerceAtMost(MAX_PX_PER_YD), (MAX_SIDE - 1) / maxOf(dx, dy), Math.sqrt(MAX_BYTES / 4.0 / (dx * dy)))
            var w = 1; var h = 1
            while (true) {
                w = Math.ceil(dx * px).toInt().coerceIn(1, MAX_SIDE); h = Math.ceil(dy * px).toInt().coerceIn(1, MAX_SIDE)
                if (w.toLong() * h * 4 <= MAX_BYTES) break
                px *= 0.97
            }
            // the tiles decoded as small as still holds the level (a tile has WmoMinimap.PX_PER_YD)
            var sample = 1
            while (WmoMinimap.PX_PER_YD / (sample * 2) >= px) sample *= 2
            val cells = tiles.map { (fid, q) ->
                Cell(fid, ((q[0] - x0) * px).toFloat(), ((y1 - q[3]) * px).toFloat(), ((q[2] - x0) * px).toFloat(), ((y1 - q[1]) * px).toFloat())
            }
            return Layout(x0, y1 - h / px, x0 + w / px, y1, px, w, h, sample, cells)
        }

        /** the cache's name for the mosaic of [pick] in [root] at [lod] (the groups and their textures: new art is a new name) */
        fun key(root: Int, groups: List<WmoMinimap.Group>, pick: List<Int>, lod: Double): String {
            var h = 1
            var n = 0
            for (gi in pick.toSortedSet()) {
                val g = groups.getOrNull(gi) ?: continue
                n++
                h = 31 * h + g.index
                for (t in g.tiles) for (v in t) h = 31 * h + v
            }
            return "citymosaic:$root:$n:${Integer.toHexString(h)}@$lod"
        }
    }

    /** a world point inside a (top, bottom, left, right) rect: x north between bottom and top, y west between right and left */
    fun inRect(r: DoubleArray, wx: Double, wy: Double): Boolean = wx in r[1]..r[0] && wy in r[3]..r[2]

    /** the capital whose footprint ([City.rect]) holds the player */
    fun cityAt(continent: Int, wx: Double, wy: Double): City? {
        if (!wx.isFinite() || !wy.isFinite()) return null
        return CAPITALS.firstOrNull { it.continent == continent && inRect(it.rect, wx, wy) }
    }

    /**
     * the indoors gate. Stormwind and Orgrimmar: the add-on says indoors. Ironforge and the Undercity ([City.positionOnly]): the player is
     * on the city's own map ([mapId], the add-on's uiMap) in EVERY case - the Ruins of Lordaeron above the Undercity are Tirisfal's, and
     * their throne room says indoors (the user's pick "Ironforge + Undercity": the lift up to the Ruins switches the city's art off) - and
     * indoors or not on a taxi ([onTaxi], review of 1525: a gryphon taking off from Ironforge's Great Forge is still on Ironforge's map -
     * [byPosition] said outdoors there, this gate drew the city's art over the outdoor map)
     */
    fun shows(city: City, indoors: Boolean, mapId: Int = city.uiMap, onTaxi: Boolean = false): Boolean =
        if (city.positionOnly) mapId == city.uiMap && (indoors || !onTaxi) else indoors

    /**
     * the capital the player is in by position alone (1525): a [City.positionOnly] one whose own map the game says the player is on
     * ([mapId]) and whose footprint ([City.rect]) holds them; never on a taxi. Null = the game's own `indoors` decides
     */
    fun byPosition(mapId: Int, continent: Int, wx: Double, wy: Double, onTaxi: Boolean = false): City? {
        if (onTaxi || !wx.isFinite() || !wy.isFinite()) return null
        return CAPITALS.firstOrNull { it.positionOnly && it.uiMap == mapId && it.continent == continent && inRect(it.rect, wx, wy) }
    }

    /** the map's indoors (MapView's every indoor decision): the add-on's own flag, or a capital by position ([byPosition]) */
    fun indoorsHere(indoors: Boolean, mapId: Int, continent: Int, wx: Double, wy: Double, onTaxi: Boolean = false): Boolean =
        indoors || byPosition(mapId, continent, wx, wy, onTaxi) != null

    /** a group's box ([x1, y1, z1, x2, y2, z2], the building's own space) holds the local point */
    fun holds(bb: FloatArray, lx: Double, ly: Double): Boolean = lx >= bb[0] && lx <= bb[3] && ly >= bb[1] && ly <= bb[4]

    private fun area(bb: FloatArray) = (bb[3] - bb[0]).toDouble() * (bb[4] - bb[1]).toDouble()

    /**
     * a floor height lies in a group from this far under its box's bottom up to its top: a floor's groups do not share one bottom
     * (Ironforge's main floor: -5 to -1 yd; the measured boxes, 2026-11-23), so the floor is a band, not a plane
     */
    const val Z_BELOW = 3.5f
    /** the first guess stands this far over the city's usual floor (the median of its groups' bottoms) */
    const val FLOOR_UP = 1f

    /**
     * what to draw: [groups] (indices into the art list, the player's own first: every group whose height range holds the [floor]),
     * [own] the player's group (-1: the player is between the groups, on none of them: the floor is still drawn around them),
     * [floor] the height the player is taken to walk at (the root's own space), [sure] = no guess
     */
    class Pick(val groups: List<Int>, val own: Int, val floor: Float, val sure: Boolean)

    /** [bb]'s height range holds [z] */
    fun atHeight(bb: FloatArray, z: Float): Boolean = z >= bb[2] - Z_BELOW && z <= bb[5]

    /**
     * THE FLOOR WALKED BY THE GROUPS (pure state: one per map view). The device has no height (UnitPosition's z is 0), so the floor is
     * carried: a built room's tracker says it ([pick]'s z), else it stays what it was while a group under the player still reaches it
     * (walking keeps to the floor), else it moves to the nearest height of the group the player walked into. With nothing known yet it
     * is the city's usual floor (the median of its groups' bottoms: Ironforge's ring and halls), else the lowest group's floor, unsure.
     * The groups drawn are every group whose height range holds that floor (the view crops them).
     */
    class Tracker {
        private var root = 0
        private var floor = Float.NaN
        private var sure = false

        fun reset() { root = 0; floor = Float.NaN; sure = false }

        fun pick(root: Int, boxes: List<FloatArray>, lx: Double, ly: Double, z: Float): Pick? {
            if (root != this.root) { reset(); this.root = root }
            if (boxes.isEmpty()) return null
            val inside = boxes.indices.filter { holds(boxes[it], lx, ly) }
            // the city's usual floor: the median of its groups' bottoms (Ironforge's ring and halls; the Undercity's walkways, not
            // its canals' bed nor the Ruins above), a little over it
            val usual = boxes.map { it[2] }.sorted().let { it[it.size / 2] } + FLOOR_UP
            if (inside.isEmpty()) {
                // between the groups (1526: a step out of a hall's box): the floor walked so far stays drawn around the player (the city
                // does not vanish), else the city's usual floor, unsure; no group is the player's own (the note says so)
                if (z.isFinite()) { floor = z; sure = true }
                else if (!floor.isFinite()) { floor = usual; sure = false }
                val f = floor
                return Pick(boxes.indices.filter { atHeight(boxes[it], f) }, -1, f, sure)
            }
            if (z.isFinite()) { floor = z; sure = true }
            else if (floor.isFinite() && inside.any { atHeight(boxes[it], floor) }) {
                // the same floor; no question left once every group under the player reaches it
                sure = sure || inside.all { atHeight(boxes[it], floor) }
            }
            else {
                // nothing known, or walked (rode an elevator) into groups that do not reach the floor: the most specific group under the
                // player (the smallest) says where they are - the city's usual floor if it reaches it, else its own floor (a group's floor
                // is at its box's bottom: the Undercity's throne room above the city, a ramp's foot); unsure while another is possible
                val small = inside.minBy { area(boxes[it]) }
                floor = if (atHeight(boxes[small], usual)) usual else boxes[small][2] + FLOOR_UP
                sure = inside.all { atHeight(boxes[it], floor) }
            }
            val f = floor
            val own = inside.filter { atHeight(boxes[it], f) }.minByOrNull { area(boxes[it]) }
                ?: inside.minBy { minOf(abs(boxes[it][2] - f), abs(boxes[it][5] - f)) }
            val groups = boxes.indices.filter { it != own && atHeight(boxes[it], f) }
            return Pick(listOf(own) + groups, own, f, sure)
        }
    }
}

/**
 * WHICH BUILT ROOM THE PLAYER IS IN (fix 3, 2026-11-23: in Ironforge 56 % of the points in the unbuilt halls fell inside some built
 * shop's WORLD box - the city is turned about 50 degrees, so a square world box sticks out far past the shop's walls - and the map drew
 * that shop's faint ghost outlines where the player stood in a hall). A room is chosen only when the point is inside its own LOCAL
 * (rotated) storey rects AND, once its height field has been read, there is walkable floor of it within [WALK_YD]; otherwise the next
 * smaller one, else none (the "no indoor map here" case). Pure (the tests): MapView supplies the candidates.
 */
internal object IndoorPick {
    /** a room near the walk (a wall, a doorway: the walk is eroded by about two cells there) still counts as inside it */
    const val WALK_YD = 3.0
    /** a point this far outside a storey's rect is still inside (the rect is the plan's own pixels, cut to its content) */
    const val RECT_MARGIN_YD = 1.0

    /** one built room under the player: [area] its world footprint (smaller = more specific), [local] inside its rotated rects,
     *  [walk] floor of it within [WALK_YD]: true / false, or null while its height field is not read */
    class Candidate(val id: String, val area: Double, val local: Boolean, val walk: Boolean?)

    /** the room to draw: the smallest one the player is inside with floor near (or not known yet); null = none */
    fun choose(cands: List<Candidate>): Candidate? =
        cands.filter { it.local && it.walk != false }.minByOrNull { it.area }

    /** the local point inside any of the storey rects ([rects]: lxmin, lxmax, lymin, lymax each) */
    fun insideLocal(rects: List<DoubleArray>, lx: Double, ly: Double, margin: Double = RECT_MARGIN_YD): Boolean =
        rects.any { r -> lx >= r[0] - margin && lx <= r[1] + margin && ly >= r[2] - margin && ly <= r[3] + margin }

    /** walkable floor within [radius] of the local point ([layersAt]: the height field's layer count at a local point; [cell] its size) */
    fun walkNear(cell: Double, lx: Double, ly: Double, radius: Double = WALK_YD, layersAt: (Double, Double) -> Int): Boolean {
        if (layersAt(lx, ly) > 0) return true
        if (!(cell > 0.0)) return false
        val k = kotlin.math.ceil(radius / cell).toInt().coerceIn(1, 12)
        val step = radius / k
        for (i in -k..k) for (j in -k..k) {
            val dx = i * step; val dy = j * step
            if (dx * dx + dy * dy > radius * radius + 1e-9) continue
            if (layersAt(lx + dx, ly + dy) > 0) return true
        }
        return false
    }
}
