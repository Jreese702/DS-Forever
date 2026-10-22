package com.jrees.wowcompanion

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * THE LIVE DANGER (the live aggro layer; LIVE_AGGRO_LAYER_DESIGN.md 5.4, 6.1, 6.4): the plates' mobs as discs over the route's static
 * danger. A COST, never a wall (the user: "some quests do expect the player to have to kill through mobs"): ShippedRoute pays
 * k x (static + live) a yard, k the same strength.
 *
 * - THE DISC: radius clamp(base - (Lp - Lm), 5, 45) yd - base 20, or FSR's learned base ([AggroFit]: npc -> creature type -> all ->
 *   20); a skull ("lv":-1) 45 yd (the user's ruling) - plus the placement's uncertainty, at full weight, fading to nothing over
 *   [EDGE_YD]. Reactions 1-3 only (hostile, unfriendly); a neutral (4) has none (the user's ruling).
 * - ITS WEIGHT: [DangerGrid.weight] of the mob's real level (grey: none, so no disc), x [ELITE_WEIGHT] for an elite, rare elite or
 *   boss, x [LIVE_GAIN]. ELITE IN THE WEIGHT, NOT THE RADIUS: the server's detect range has no elite term (AzerothCore's
 *   Creature::GetAttackDistance; FSR measured none either) - an elite notices you no sooner, it costs more when it does. x the
 *   placement's trust ([trust]), x [OTHER_WEIGHT] for a mob already fighting someone else, fading over [LiveMobs.GONE_FADE_MS] once its
 *   plate is gone.
 * - THE STATIC SHARE DROPPED ([drops]): a spawn the plates show absent or dead (held [LiveMobs.HOLD_MS]), or one a plate is snapped to
 *   for sure (its live disc replaces the 60 yd blur; a neutral's too: it is no danger) - its own share of the grid taken off again.
 *
 * Pure: world yards (x north, y west). A [Snapshot] is immutable: made on the UI thread, read on the route thread.
 */
object LiveDanger {
    const val MIN_R = 5.0
    const val MAX_R = 45.0
    const val SKULL_R = 45.0
    const val EDGE_YD = 5.0
    const val ELITE_WEIGHT = 1.5
    /** a disc's danger against the static grid's (a spawn's weight spread over 60 yd, CAP 4 a cell): a mob seen here and now is
     *  denser than a QuestieDB maybe - twice its weight inside the disc */
    const val LIVE_GAIN = 2.0f
    const val LIVE_CAP = 6f
    /** a mob in combat with someone else (cb 1, no threat on us) is busy */
    const val OTHER_WEIGHT = 0.5
    /** a forced replan for a new mob at most this often (ms; the 1-2 s the user's ask needs, against SafePathV2.REPLAN_MS) */
    const val FORCE_MS = 1_000L

    /** the trust in a placement: a sure spawn snap 1, an unclear one 0.8, a fix 0.9, a camera sector 0.8, a facing one 0.6 (low:
     *  design 5.6), a ring band 0.35 (0.5 in a half-plane: half the ground) */
    fun trust(p: LivePlace.Placed): Double = when (p.how) {
        LivePlace.How.SPAWN -> if (p.sure) 1.0 else 0.8
        LivePlace.How.FIX -> 0.9
        LivePlace.How.SECTOR -> if (p.sd <= Math.toRadians(LivePlace.SD_CAMERA_DEG) + 1e-9) 0.8 else 0.6
        LivePlace.How.RING -> if (p.half != null) 0.5 else 0.35
    }

    /** the aggro radius (yd) of a mob of level [lm] (-1 skull; null unknown: equal level) for a player of [lp], [base] the equal-level
     *  radius (FSR's formula, its learned base) */
    fun radius(lm: Int?, lp: Int, base: Double = AggroFit.DEFAULT_BASE): Double {
        if (lm == -1) return SKULL_R
        val l = lm ?: lp
        return (base - (lp - l)).coerceIn(MIN_R, MAX_R)
    }

    /** a live mob's weight before its trust: 0 for a neutral or a grey; an elite x [ELITE_WEIGHT] */
    fun weight(m: AggroDoc.Mob, lm: Int?, lp: Int): Float {
        if (!m.aggressive) return 0f
        val l = if (lm == -1) lp + 10 else lm ?: lp
        val w = DangerGrid.weight(l, l, false, lp)
        return if (m.elite) (w * ELITE_WEIGHT).toFloat() else w
    }

    /** one disc: [id] its plate's track; a point ([ring] false: centre, [r] radius + [u]) or a band ([ring]: round ([x], [y]) from
     *  [a] - r to [b] + r, in the half-plane facing [half] when set) */
    class Disc(val id: Long, val x: Double, val y: Double, val r: Double, val u: Double, val w: Float, val ring: Boolean = false,
               val a: Double = 0.0, val b: Double = 0.0, val half: Double? = null, val how: LivePlace.How = LivePlace.How.SPAWN,
               val npc: Int? = null, val lv: Int? = null,
               /** its place CONFIRMED: a sure spawn snap or a fix from the rings (the user's "Only if sure": only these move the route) */
               val sure: Boolean = false) {
        val reach get() = r + u
        fun at(px: Double, py: Double): Float {
            val d = hypot(px - x, py - y)
            if (!ring) return fade(d - reach) * w
            if (half != null && (px - x) * cos(half) + (py - y) * sin(half) < -r) return 0f
            val out = max(a - r - d, d - b - r)
            return fade(out) * w
        }
        private fun fade(out: Double): Float = if (out <= 0.0) 1f else if (out >= EDGE_YD) 0f else (1.0 - out / EDGE_YD).toFloat()
    }
    /** a spawn's static share taken off ([w]: its DangerGrid.weight, spread as the grid spreads it) */
    class Drop(val x: Double, val y: Double, val w: Float, val why: String) {
        fun at(px: Double, py: Double): Float {
            val d = hypot(px - x, py - y)
            return if (d >= DangerGrid.AGGRO_YD) 0f else w * (1f - (d / DangerGrid.AGGRO_YD).toFloat() * 0.5f)
        }
    }

    /** the live layer at one moment: [discs], [drops], [mobs] the aggressive plates counted (placed or not), [off] the off record's why */
    class Snapshot(val discs: List<Disc>, val drops: List<Drop>, val mobs: Int, val off: String? = null) {
        val empty get() = discs.isEmpty() && drops.isEmpty()
        /** THE ROUTE'S SHARE (the user's routing strategy, test71: "Only if sure"): the discs whose place is confirmed ([Disc.sure]); a
         *  band or an unclear plate is display only (the tint, the bottom map's circles), never a cost or a forced replan */
        fun confirmed(): Snapshot = if (discs.all { it.sure }) this else Snapshot(discs.filter { it.sure }, drops, discs.count { it.sure }, off)
        /** the live danger at a world point (capped [LIVE_CAP]) */
        fun at(x: Double, y: Double): Float { var s = 0f; for (d in discs) s += d.at(x, y); return min(LIVE_CAP, s) }
        /** the static danger to take off at a world point */
        fun drop(x: Double, y: Double): Float { var s = 0f; for (d in drops) s += d.at(x, y); return s }
        /** the discs a plan was made with: their tracks (point discs only - a ring band is everywhere round the player) */
        val pointIds: Set<Long> get() = discs.filter { !it.ring }.map { it.id }.toHashSet()
        /** the route line's words: "live D (n mobs)" */
        fun words(d: Double) = "live ${d.toInt()} ($mobs mobs)"
    }
    val NONE = Snapshot(emptyList(), emptyList(), 0)

    /** the static danger [static] with [live] over it: the dropped spawns' shares off (never below 0), the discs on */
    fun combine(static: ((Double, Double) -> Float)?, live: Snapshot?): ((Double, Double) -> Float)? {
        if (static == null) return null
        if (live == null || live.empty) return static
        return { x, y -> val s = static(x, y); (if (s.isNaN()) 0f else max(0f, s - live.drop(x, y))) + live.at(x, y) }
    }

    /**
     * A MOB ALREADY ANSWERED (test71: "a new lv 10 mob (npc 253782, spawn) 18 yd round (2436,447) touches the way: planned again" every
     * 20-30 s, the same 482 yd plan each time - its plate re-tracked under a new id, so "new" again): a forced replan that found the
     * same way keeps the mob that forced it, by its npc and place, not its track id; a disc of the same npc within [ANSWERED_YD] of
     * that place forces no replan again (until the way changes: the caller drops its list)
     */
    class Answered(val npc: Int?, val x: Double, val y: Double) {
        fun matches(d: Disc): Boolean = d.npc == npc && hypot(d.x - x, d.y - y) <= ANSWERED_YD
    }
    const val ANSWERED_YD = 10.0
    /** a forced replan's way is the kept one's when its yards are within this of the kept way's rest */
    const val SAME_WAY_YD = 5.0

    /**
     * THE FORCED REPLAN (the user's ask: react to a mob that walks between me and where I am going): a point disc of [live] not in
     * [known] (the discs the kept way was planned with) whose reach touches the way still ahead ([rest], the player first) - not one
     * within the goal's free yards ([goal]; the route may walk through those). The first such disc, or null.
     */
    fun newOnRoute(rest: List<DoubleArray>, live: Snapshot, known: Set<Long>, goal: DoubleArray?, goalFreeYd: Double = DangerGrid.GOAL_FREE_YD,
                   answered: List<Answered> = emptyList()): Disc? {
        if (rest.size < 2) return null
        for (d in live.discs) {
            if (d.ring || d.id in known || d.w <= 0f) continue
            if (answered.any { it.matches(d) }) continue
            if (goal != null && hypot(d.x - goal[0], d.y - goal[1]) <= goalFreeYd) continue
            if (SafePathV2.offWay(doubleArrayOf(d.x, d.y), rest) <= d.reach) return d
        }
        return null
    }
}
