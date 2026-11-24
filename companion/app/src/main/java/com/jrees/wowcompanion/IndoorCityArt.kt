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
 * Pure (the tests): MapView reads the art index (wmominimap.json) and draws.
 */
internal object IndoorCityArt {
    /**
     * One capital. [l2w]: its root's placement as MapView's Place reads it (ox, oy, cos, sin; the PC host bake 2026-10-13, the same for
     * every one of its rooms; a built room of the root on this continent wins over it). [rect]: the city's painted map's world rect
     * (top, bottom, left, right: worldmap/<uiMap>/index.json), the footprint when the art set has no index for it.
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
            doubleArrayOf(-4569.2412, -5096.8457, -713.5914, -1504.2164), positionOnly = false),
        City("Undercity", 7675285, 1458, 0, doubleArrayOf(1647.7174479166679, 239.83072916666788, -0.9999619230641713, 0.008726535498374403),
            doubleArrayOf(1877.9453, 1237.8412, 873.1926, -86.1824), positionOnly = false),
        City("Orgrimmar", 7964229, 1454, 1, doubleArrayOf(1500.5026041666679, -4418.192708333332, 0.8290375725550417, 0.5591929034707469),
            doubleArrayOf(2273.8772, 1338.4606, -3680.6011, -5083.2056), positionOnly = false),
    )

    fun byRoot(root: Int): City? = CAPITALS.firstOrNull { it.root == root }

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

    /** a world point inside a (top, bottom, left, right) rect: x north between bottom and top, y west between right and left */
    fun inRect(r: DoubleArray, wx: Double, wy: Double): Boolean = wx in r[1]..r[0] && wy in r[3]..r[2]

    /** the capital whose footprint holds the player ([rectOf]: the painted map's rect if the art set has it, else the city's own) */
    fun cityAt(continent: Int, wx: Double, wy: Double, rectOf: (City) -> DoubleArray? = { null }): City? {
        if (!wx.isFinite() || !wy.isFinite()) return null
        return CAPITALS.firstOrNull { it.continent == continent && inRect(rectOf(it) ?: it.rect, wx, wy) }
    }

    /** the indoors gate: the add-on says indoors, or this city shows its art by position alone ([City.positionOnly]) */
    fun shows(city: City, indoors: Boolean): Boolean = indoors || city.positionOnly

    /**
     * THE CITIES' PAINTED MAPS, LOOKED UP OFF THE DRAWING THREAD (review of 2026-11-23: MapView read worldmap/<id>/index.json inside its
     * draw, and a city with no painted map was read again about every 10 s, on every frame after that). [get] answers from memory only:
     * a city not looked up yet is read once on [background] (MapView's plans thread), its answer lands through [post] on the drawing
     * thread and [onArrive] redraws; a miss (no Map room art for it yet) is asked again only after [MISS_MS]. Drawing-thread only, but
     * [read] runs on [background]. Pure (the tests drive the three hooks).
     */
    class PaintedIndex(
        private val read: (Int) -> Painted?,
        private val background: (Runnable) -> Unit,
        private val post: (Runnable) -> Unit,
        private val now: () -> Long,
        private val onArrive: () -> Unit = {},
    ) {
        /** one city's painted map: its continent and world rect (top, bottom, left, right) */
        class Painted(val id: Int, val continent: Int, val rect: DoubleArray)

        private val hits = HashMap<Int, Painted>()
        private val missedAt = HashMap<Int, Long>()
        private val asking = HashSet<Int>()

        fun get(id: Int): Painted? {
            hits[id]?.let { return it }
            if (id in asking) return null
            val miss = missedAt[id]
            if (miss != null && now() - miss < MISS_MS) return null
            asking.add(id)
            background(Runnable {
                val p = runCatching { read(id) }.getOrNull()
                post(Runnable {
                    asking.remove(id)
                    if (p != null) { hits[id] = p; missedAt.remove(id); onArrive() } else missedAt[id] = now()
                })
            })
            return null
        }

        companion object {
            /** a city with no painted map is looked up again after this long (the Map room's art may be built meanwhile) */
            const val MISS_MS = 60_000L
        }
    }

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
     * [own] the player's group, [floor] the height the player is taken to walk at (the root's own space), [sure] = no guess
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
            val inside = boxes.indices.filter { holds(boxes[it], lx, ly) }
            if (inside.isEmpty()) return null                     // between the groups (out of the city's halls): nothing here
            // the city's usual floor: the median of its groups' bottoms (Ironforge's ring and halls; the Undercity's walkways, not
            // its canals' bed nor the Ruins above), a little over it
            val usual = boxes.map { it[2] }.sorted().let { it[it.size / 2] } + FLOOR_UP
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
