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
    const val FRESH_MS = 5_000L            // a plate not seen this long: no ring (the live layer's own fade handles the route)

    /** the reach (yd) of a mob of level [lm] (-1 a skull; null unknown: [bracket]'s midpoint, else the player's level) for a player of [lp] */
    fun radius(lm: Int?, lp: Int, bracket: IntRange? = null): Double {
        val l = when {
            lm == -1 -> lp + SKULL_PLUS
            lm != null -> lm
            bracket != null -> (bracket.first + bracket.last + 1) / 2
            else -> lp
        }
        return (BASE_YD + (l - lp)).coerceIn(MIN_YD, MAX_YD)
    }

    /** one ring: [id] the plate's track, world ([x], [y]), [r] yd, [inside] the player stands in it */
    class Ring(val id: Long, val x: Double, val y: Double, val r: Double, val inside: Boolean)

    /** a live plate as the rings read it (a [LiveMobs.Track]'s mob and placement) */
    class Plate(val id: Long, val mob: AggroDoc.Mob, val placed: LivePlace.Placed?, val lastMs: Long)

    /** the rings of [plates] for a player of level [lp] at world ([px], [py]) (NaN: unknown, none inside) at [now] */
    fun of(plates: List<Plate>, lp: Int, px: Double, py: Double, now: Long): List<Ring> = plates.mapNotNull { t ->
        val m = t.mob; val p = t.placed ?: return@mapNotNull null
        if (!m.aggressive || m.dd || m.td == true || m.cb == true) return@mapNotNull null
        if (p.how == LivePlace.How.RING) return@mapNotNull null
        if (now - t.lastMs > FRESH_MS) return@mapNotNull null
        val r = radius(m.lv, lp, p.spawn?.let { it.minLv..it.maxLv })
        Ring(t.id, p.x, p.y, r, !px.isNaN() && !py.isNaN() && hypot(px - p.x, py - p.y) <= r)
    }

}
