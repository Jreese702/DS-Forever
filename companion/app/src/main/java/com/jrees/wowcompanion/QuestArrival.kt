package com.jrees.wowcompanion

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * ARRIVED = INSIDE THE QUEST'S AREA (the user on test60: "on the bottom screen the arrow is staying up to go towards a quest even when I
 * am in the quests blue zone"): the focused quest's blue area - the game's shapes, QuestieDB's areas, QuestTrace's, or the circle round
 * a point that has none - holds the player, so no "go there" arrow, line or pin. [Gate] keeps it from flickering on the edge: in once
 * inside the shape, out only [LEAVE_YD] past it. Pure: map 0..1 coordinates, [ax] / [ay] the map's yards per unit.
 */
object QuestArrival {
    /** out again only this far outside the shape (yd): a walk along its edge does not flicker */
    const val LEAVE_YD = 6.0

    /** a polygon (map 0..1: [xs] / [ys]) or a circle ([cx], [cy], [r] yd) */
    sealed class Shape {
        class Poly(val xs: FloatArray, val ys: FloatArray) : Shape()
        class Circle(val cx: Double, val cy: Double, val r: Double) : Shape()
    }

    /** the player at ([px], [py])'s yards OUTSIDE [s] (negative: inside, by that much from its edge) */
    fun outside(s: Shape, px: Double, py: Double, ax: Double, ay: Double): Double = when (s) {
        is Shape.Circle -> hypot((px - s.cx) * ax, (py - s.cy) * ay) - s.r
        is Shape.Poly -> {
            val n = s.xs.size
            if (n < 3) Double.MAX_VALUE else {
                var inside = false; var best = Double.MAX_VALUE
                var j = n - 1
                for (i in 0 until n) {
                    val xi = s.xs[i] * ax; val yi = s.ys[i] * ay; val xj = s.xs[j] * ax; val yj = s.ys[j] * ay
                    val x = px * ax; val y = py * ay
                    if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                    best = min(best, segDist(x, y, xi, yi, xj, yj))
                    j = i
                }
                if (inside) -best else best
            }
        }
    }

    /** the nearest of [shapes]: the least yards outside (the most inside); [Double.MAX_VALUE] none */
    fun outside(shapes: List<Shape>, px: Double, py: Double, ax: Double, ay: Double): Double =
        shapes.minOfOrNull { outside(it, px, py, ax, ay) } ?: Double.MAX_VALUE

    /** the arrival with its hysteresis: [arrived] once the player is inside a shape, until [LEAVE_YD] outside all of them */
    class Gate {
        var arrived = false; private set
        /** the last look's yards outside (the log) */
        var lastOutside = Double.MAX_VALUE; private set
        fun look(shapes: List<Shape>, px: Double, py: Double, ax: Double, ay: Double): Boolean {
            if (px.isNaN() || py.isNaN() || shapes.isEmpty()) { arrived = false; lastOutside = Double.MAX_VALUE; return false }
            val d = outside(shapes, px, py, ax, ay)
            lastOutside = d
            arrived = if (arrived) d <= LEAVE_YD else d <= 0.0
            return arrived
        }
        fun reset() { arrived = false; lastOutside = Double.MAX_VALUE }
    }

    private fun segDist(x: Double, y: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val dx = x2 - x1; val dy = y2 - y1; val l2 = dx * dx + dy * dy
        val t = if (l2 <= 0.0) 0.0 else max(0.0, min(1.0, ((x - x1) * dx + (y - y1) * dy) / l2))
        return hypot(x - (x1 + t * dx), y - (y1 + t * dy))
    }
}
