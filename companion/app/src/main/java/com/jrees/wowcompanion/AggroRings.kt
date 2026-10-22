package com.jrees.wowcompanion

import kotlin.math.hypot

/**
 * THE AGGRO RINGS on the bottom map (the user, 2026-10-21: "is it possible to increase the size of mobs bottom unit circle before they
 * are aggroed to show their aggro radius?"; picked "Both", the map's look as rendered): round each unpulled hostile mob the live layer
 * places ([LiveMobs]: the add-on's nameplates), its aggro reach - one merged shape for a pack, soft yellow, red where the player stands
 * inside; none once the mob is in combat. THE RULE, shared with the top screen's ground rings (Interact, the same numbers on both
 * screens): [radius]. Only a plate placed at a point (a spawn, a fix, a camera sector): a bracket's band has no centre to draw.
 * Pure: no Android.
 */
object AggroRings {
    const val BASE_YD = 20.0               // an equal-level mob's reach
    const val MIN_YD = 5.0
    const val MAX_YD = 45.0
    const val SKULL_PLUS = 10              // a "??" (skull) mob: the player's level + this (30 yd)
    /** test52 (the user: "Wrong place"): a ring only where the mob's place is known within this (yd) - a sure or near-sure spawn snap,
     *  a fix, a camera bearing over a narrow bracket. A facing-only bearing while standing, or a wide bracket's middle, is no place to
     *  draw a crisp ring (it stays in the route's danger, weighted by its trust) */
    const val MAX_U_YD = 8.0

    /** the reach (yd) of a mob of level [lm] (-1 a skull; null unknown: [bracket]'s midpoint, else the player's level) for a player of
     *  [lp]: THE SHARED RULE, fitted and learned ([AggroFit.radius]: the user's "Fit it"); [n] / [ct] its npc / type (their learned base) */
    fun radius(lm: Int?, lp: Int, bracket: IntRange? = null, n: Int? = null, ct: Int? = null): Double = AggroFit.radius(lm, lp, n, ct, bracket)

    // ---------------------------------------------------------------- the spawn circles

    /** the user, 2026-10-22 ("Spawn circles"): spawns this near the player get a circle, at most [SPAWN_CAP] (the nearest) */
    const val SPAWN_NEAR_YD = 125.0
    const val SPAWN_CAP = 40

    /** a known spawn's circle: [key] the spawn, world ([x], [y]), [r] yd (the shared rule at its level range's middle), [inside] the
     *  player stands in it, [solid] a live plate of its npc is confirmed there (else faint: a mob may or may not stand there now) */
    class SpawnRing(val key: Int, val x: Double, val y: Double, val r: Double, val inside: Boolean, val solid: Boolean)

    /**
     * THE SPAWN CIRCLES (the user's "Spawn circles"): round each KNOWN SPAWN of a creature hostile to the player (QuestieDB's, this map's)
     * within [SPAWN_NEAR_YD], its aggro reach by the shared rule ([radius] at its level range's middle, its npc's learned base) - FAINT
     * by default (a spawn, not a mob seen); SOLID when a live plate of that npc is CONFIRMED there: its distance bands from the player's
     * spots hold the spawn ([LivePlace.bracket] widened by the wander), the plate unpulled. A spawn a live ring already stands on
     * ([liveKeys]: a sure snap) is left to that ring. The nearest [SPAWN_CAP].
     */
    fun spawnRings(spawns: List<LivePlace.SpawnPt>, plates: List<Plate>, lp: Int, px: Double, py: Double, maxd: Double? = null,
                   liveKeys: Set<Int> = emptySet()): List<SpawnRing> {
        if (px.isNaN() || py.isNaN()) return emptyList()
        val near = spawns.asSequence().filter { it.hostileToMe && it.key !in liveKeys }
            .map { it to hypot(it.x - px, it.y - py) }.filter { it.second <= SPAWN_NEAR_YD }
            .sortedBy { it.second }.take(SPAWN_CAP).toList()
        val live = plates.filter { t -> t.mob.aggressive && !t.mob.dd && t.mob.td != true && t.mob.cb != true && t.mob.n != null }
        return near.map { (s, d) ->
            val r = radius(null, lp, s.minLv..s.maxLv, s.npc)
            val solid = live.any { t -> t.mob.n == s.npc && (t.placed?.spawn?.key == s.key ||
                LivePlace.bracket(t.mob, maxd).let { (a, b) -> d >= a - LivePlace.WANDER_YD && d <= b + LivePlace.WANDER_YD }) }
            SpawnRing(s.key, s.x, s.y, r, d <= r, solid)
        }
    }

    /** one ring: [id] the plate's track, world ([x], [y]), [r] yd, [inside] the player stands in it; [why] its placement's inputs (the log) */
    class Ring(val id: Long, val x: Double, val y: Double, val r: Double, val inside: Boolean, val why: String = "",
               /** the spawn it stands on (a sure snap), null none: that spawn's own circle is left to it */
               val spawnKey: Int? = null)
    /** the rings of a look, and the plates left out by reason (the log's counts) */
    class Look(val rings: List<Ring>, val plates: Int, val combat: Int, val band: Int, val vague: Int, val other: Int) {
        fun words() = "${rings.size} rings of $plates plates (in combat $combat, distance band only $band, place too vague $vague, other $other)"
    }

    /** a live plate as the rings read it (a [LiveMobs.Track]'s mob and placement) */
    class Plate(val id: Long, val mob: AggroDoc.Mob, val placed: LivePlace.Placed?, val lastMs: Long)

    /** the rings of [plates] for a player of level [lp] at world ([px], [py]) (NaN: unknown, none inside) */
    fun of(plates: List<Plate>, lp: Int, px: Double, py: Double): List<Ring> = look(plates, lp, px, py).rings

    /**
     * whether [p] is an HONEST centre for a ring (test52: plate:GetCenter is secret on Forever, so no plate carries a screen bearing;
     * a ring in the wrong place is worse than none): a SURE snap to its own spawn (the only spawn of its creature its distance bands
     * fit, or clearly the best); a FIX from the player's own band readings as they walked (within [MAX_U_YD]); a bearing from the
     * compass's camera yaw ("c") over a narrow bracket (within [MAX_U_YD]). Never an unclear snap (two spawns fit as well), a facing
     * taken as the yaw, a bracket's middle on no bearing.
     */
    fun honest(m: AggroDoc.Mob, p: LivePlace.Placed): Boolean = when (p.how) {
        LivePlace.How.SPAWN -> p.sure
        LivePlace.How.FIX -> p.u <= MAX_U_YD
        LivePlace.How.SECTOR -> m.bs == "c" && p.u <= MAX_U_YD
        LivePlace.How.RING -> false
    }

    fun look(plates: List<Plate>, lp: Int, px: Double, py: Double): Look {
        val out = ArrayList<Ring>(); var combat = 0; var band = 0; var vague = 0; var other = 0
        for (t in plates) {
            val m = t.mob; val p = t.placed
            when {
                !m.aggressive || m.dd || m.td == true -> { other++; continue }
                m.cb == true -> { combat++; continue }
                p == null || p.how == LivePlace.How.RING -> { band++; continue }
                !honest(m, p) -> { vague++; continue }
            }
            p!!
            val r = radius(m.lv, lp, p.spawn?.let { it.minLv..it.maxLv }, m.n, m.ct)
            val why = "${p.how}${if (p.spawn != null) " spawn ${p.spawn.key}${if (p.sure) " sure" else ""}" else ""} at ${p.x.toInt()},${p.y.toInt()} " +
                "u ${"%.1f".format(p.u)} yd; band ${m.a?.toInt() ?: "-"}..${m.b?.toInt() ?: "-"}, br ${m.br?.let { "%.2f".format(it) } ?: "-"} (${m.bs ?: "-"}), lv ${m.lv ?: "-"} -> ${r.toInt()} yd"
            out.add(Ring(t.id, p.x, p.y, r, !px.isNaN() && !py.isNaN() && hypot(px - p.x, py - p.y) <= r, why,
                p.spawn?.key?.takeIf { p.how == LivePlace.How.SPAWN && p.sure }))
        }
        return Look(out, plates.size, combat, band, vague, other)
    }

}
