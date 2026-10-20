package com.jrees.wowcompanion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * WHERE A LIVE MOB STANDS (the live aggro layer; LIVE_AGGRO_LAYER_DESIGN.md 5.3 and 5.6). No add-on API gives an enemy's world
 * position: a plate gives a distance BRACKET [a, b] round the player's exact spot, maybe a screen x or (ag1) a world bearing, and
 * out of combat its npc id. Pure: world yards (x north, y west), angles in radians counter-clockwise from north (as the doc's me.f).
 *
 * The rules, in order of what wins:
 * The bearing (the add-on's `br`, ag1) is the PREFERRED placement: a spawn is snapped to only where it agrees with it (inside the
 * bearing's spread and the bracket), which is the same sector made exact; no spawn agrees, the sector stands.
 * 1. SPAWN: the npc id's QuestieDB spawns that fit every ring of the plate's history (each bracket from each player spot as we walk:
 *    trilateration), and the bearing when there is one - the clear best (or the only one) is the mob, confirmed alive there
 *    ([SPAWN_U]). Not clear: the most consistent, then the nearest, with a wider margin ([AMBIG_U]) and not "sure" (the spawn's static
 *    danger kept). A mob first seen in combat (no npc id) is never snapped.
 * 2. a SECTOR from the bearing (the add-on's `br`; else sx through the screen's [HFOV_DEG] with the camera's yaw taken as the
 *    player's facing - only while moving, weighted low, design 5.6) and the bracket; or a FIX from the rings alone (their feasible
 *    points within [FIX_SPREAD_YD]) - whichever is the tighter.
 * 3. a RING band at the bracket round the player, in the half-plane in front of the facing when the plate is on screen (sx known).
 */
object LivePlace {
    /** the game camera's horizontal field of view (design 5.6: about 90 degrees at 16:9; unmeasured) */
    const val HFOV_DEG = 90.0
    /** the bearing's spread: the add-on's camera yaw (bs "c", the compass's dead reckoning) and the facing taken as the yaw */
    const val SD_CAMERA_DEG = 10.0
    const val SD_FACING_DEG = 25.0
    /** a facing-based `br` while the player stands still (the camera may be panned): wider still */
    const val SD_STILL_DEG = 40.0
    /** a mob stands this far round its spawn point (design 5.1: 5-10 yd wander) */
    const val WANDER_YD = 6.0
    /** a spawn fits the rings with this little left over (yd) */
    const val FEASIBLE_YD = 2.0
    /** the best spawn wins outright when this far (yd of misfit) ahead of the next */
    const val SNAP_CLEAR_YD = 5.0
    /** the margins: a sure snap (design 5.4's "confirmed 3 yd" + the wander's spread), an unclear one */
    const val SPAWN_U = 4.0
    const val AMBIG_U = 8.0
    /** a fix from the rings: their feasible points no farther than this from their middle, the rings' spots at least [FIX_BASE_YD] apart */
    const val FIX_SPREAD_YD = 10.0
    const val FIX_BASE_YD = 8.0
    /** b omitted (beyond our largest check): bounded by cv.maxd, else this (the contract) */
    const val DEFAULT_MAX_YD = 41.0

    enum class How { SPAWN, FIX, SECTOR, RING }

    /** a bracket read from player spot ([x], [y]) at [ms] */
    class Ring(val x: Double, val y: Double, val a: Double, val b: Double, val ms: Long)
    /** a QuestieDB spawn in world yards: [key] its index in the map's list, [npc] its creature */
    class SpawnPt(val key: Int, val npc: Int, val x: Double, val y: Double, val minLv: Int, val maxLv: Int, val elite: Boolean,
                  val hostileToMe: Boolean)
    /** a placement: the mob at ([x], [y]) give or take [u] yd ([How.RING]: the band [a]..[b] round ([x], [y]), [half] the facing
     *  of its half-plane or null); [spawn] the spawn snapped to, [sure] the snap is clear */
    class Placed(val how: How, val x: Double, val y: Double, val u: Double, val a: Double = 0.0, val b: Double = 0.0,
                 val half: Double? = null, val spawn: SpawnPt? = null, val sure: Boolean = false, val sd: Double = 0.0)

    /** the plate's bracket: [a] 0 when unknown, [b] bounded by [maxd] (or [DEFAULT_MAX_YD]) when omitted, never below a */
    fun bracket(m: AggroDoc.Mob, maxd: Double?): Pair<Double, Double> {
        val a = m.a ?: 0.0
        val bound = maxd ?: DEFAULT_MAX_YD
        val b = m.b ?: max(bound, a + 5.0)
        return a to max(a, b)
    }

    /** the bearing to the mob and its spread (rad), or null: the add-on's own `br` (a facing-based one wider while still), else
     *  sx through the FOV with the yaw = the facing, only while [moving] and the plate not clamped */
    fun bearing(m: AggroDoc.Mob, me: AggroDoc.Me, moving: Boolean): Pair<Double, Double>? {
        m.br?.let { br ->
            // the add-on's own bearing is the preferred placement (Map's ag1 note): a camera yaw ("c") tight, a facing yaw ("f") looser,
            // loosest while standing (the camera may be panned off the facing)
            if (m.bs == "c") return br to Math.toRadians(SD_CAMERA_DEG)
            return br to Math.toRadians(if (moving) SD_FACING_DEG else SD_STILL_DEG)
        }
        val sx = m.sx ?: return null; val f = me.f ?: return null
        if (!moving || m.cl == true) return null
        return (f - atan(sx * tan(Math.toRadians(HFOV_DEG / 2)))) to Math.toRadians(SD_FACING_DEG)
    }

    fun angleTo(fx: Double, fy: Double, tx: Double, ty: Double) = atan2(ty - fy, tx - fx)
    fun angDiff(a: Double, b: Double): Double { var d = (a - b) % (2 * PI); if (d > PI) d -= 2 * PI; if (d < -PI) d += 2 * PI; return abs(d) }

    /** how far (yd) point ([x], [y]) lies outside ring [r] widened by [w] */
    fun miss(x: Double, y: Double, r: Ring, w: Double): Double { val d = hypot(x - r.x, y - r.y); return max(0.0, max(r.a - w - d, d - r.b - w)) }

    /**
     * place one plate [m]: [me] the player now, [rings] its bracket history (the newest last; the current reading among them),
     * [spawns] its npc's spawns (none: no snap), [taken] spawns other plates hold, [moving] the player walks (the yaw ~ the facing)
     */
    fun place(m: AggroDoc.Mob, me: AggroDoc.Me, rings: List<Ring>, spawns: List<SpawnPt>, taken: Set<Int>, moving: Boolean, maxd: Double?): Placed {
        val (a, b) = bracket(m, maxd)
        val mid = (a + b) / 2; val half = (b - a) / 2
        val brg = bearing(m, me, moving)
        val hist = rings.ifEmpty { listOf(Ring(me.x, me.y, a, b, 0L)) }
        // 1. the spawn snap (never for a plate first seen in combat: no npc id)
        if (m.n != null && spawns.isNotEmpty()) {
            val scored = spawns.filter { it.key !in taken }.map { s ->
                var sc = hist.sumOf { miss(s.x, s.y, it, WANDER_YD) }
                if (brg != null) {
                    val d = hypot(s.x - me.x, s.y - me.y)
                    val off = angDiff(angleTo(me.x, me.y, s.x, s.y), brg.first) - 2 * brg.second
                    if (off > 0 && d > WANDER_YD) sc += off * d
                }
                s to sc
            }.filter { it.second <= FEASIBLE_YD }
                .sortedWith(compareBy<Pair<SpawnPt, Double>> { it.second }.thenBy { abs(hypot(it.first.x - me.x, it.first.y - me.y) - mid) })
            if (scored.isNotEmpty()) {
                val best = scored[0]
                val clear = scored.size == 1 || scored[1].second - best.second >= SNAP_CLEAR_YD
                if (clear) return Placed(How.SPAWN, best.first.x, best.first.y, SPAWN_U, spawn = best.first, sure = true)
                if (brg == null) {
                    // most consistent, then nearest the player
                    val pick = scored.filter { it.second - best.second < SNAP_CLEAR_YD }
                        .minByOrNull { hypot(it.first.x - me.x, it.first.y - me.y) }!!.first
                    return Placed(How.SPAWN, pick.x, pick.y, AMBIG_U, spawn = pick, sure = false)
                }
            }
        }
        // 2. the sector and the fix: the tighter
        val sector = brg?.let { (th, sd) -> Placed(How.SECTOR, me.x + mid * cos(th), me.y + mid * sin(th), hypot(half, mid * sd), sd = sd) }
        val fix = fix(hist, brg)
        val pick = listOfNotNull(sector, fix).minByOrNull { it.u }
        if (pick != null) return pick
        // 3. the band round the player
        return Placed(How.RING, me.x, me.y, 0.0, a, b, if (m.sx != null || m.br != null) (m.br ?: me.f) else null)
    }

    /** a fix from the rings alone (two or more, their spots [FIX_BASE_YD] apart): the newest ring's points every 5 degrees that fit
     *  every ring, when they gather within [FIX_SPREAD_YD] of their middle */
    fun fix(rings: List<Ring>, brg: Pair<Double, Double>?): Placed? {
        if (rings.size < 2) return null
        val last = rings.last()
        if (rings.none { hypot(it.x - last.x, it.y - last.y) >= FIX_BASE_YD }) return null
        val pts = ArrayList<DoubleArray>()
        for (rr in listOf(last.a, (last.a + last.b) / 2, last.b)) {
            if (rr <= 0.5) continue
            for (k in 0 until 72) {
                val th = k * 5.0 * PI / 180
                if (brg != null && angDiff(th, brg.first) > 3 * brg.second) continue
                val x = last.x + rr * cos(th); val y = last.y + rr * sin(th)
                if (rings.all { miss(x, y, it, 1.0) <= 0.0 }) pts.add(doubleArrayOf(x, y))
            }
        }
        if (pts.isEmpty()) return null
        val cx = pts.sumOf { it[0] } / pts.size; val cy = pts.sumOf { it[1] } / pts.size
        val spread = pts.maxOf { hypot(it[0] - cx, it[1] - cy) }
        if (spread > FIX_SPREAD_YD) return null
        return Placed(How.FIX, cx, cy, spread + (last.b - last.a) / 2)
    }
}
