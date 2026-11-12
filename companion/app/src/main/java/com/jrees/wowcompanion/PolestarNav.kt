package com.jrees.wowcompanion

import org.json.JSONObject
import kotlin.math.abs

/**
 * POLESTAR AS THE ROUTER (b-route-polestar; the user, 2026-10-17: "Quest tracking: Vanilla | QuestTrail | Polestar"). With the
 * setting on Polestar the focused quest's goal is handed to Polestar's public Nav API through Map.lua's pad row "pnav" (GO with the
 * goal's uiMapID and 0..1 place, KEEP while the app still wants it, CANCEL when it does not), and the walking route Polestar works
 * out comes back as {"type":"pnavroute"} documents for the map's line. Polestar draws its own arrow, so the game's pin (RouteWaypoint)
 * stands down in this mode. Polestar missing or refusing: the app's own route stays, and the map says why ([why]).
 * Pure: which taps, and the route as legs of world points.
 */
class PolestarNav {
    private var active = false
    private var sent: DoubleArray? = null               // map, x, y
    private var setAt = Long.MIN_VALUE / 2; private var keepAt = Long.MIN_VALUE / 2
    private var refusedUntil = Long.MIN_VALUE / 2
    /** why Polestar is not routing (null = it is, or nothing was asked yet) */
    var why: String? = null; private set

    /** One look at the goal at [nowMs]: [want] = (uiMapID, x, y) or null. The row's columns to tap, in order (empty = nothing). */
    fun tick(want: DoubleArray?, nowMs: Long): List<Int> {
        if (want == null) {
            if (!active) return emptyList()
            active = false; sent = null; route = emptyList(); liveFrom = 1
            return listOf(CANCEL)
        }
        if (nowMs < refusedUntil) return emptyList()
        val s = sent
        val moved = !active || s == null || s[0] != want[0] || abs(want[1] - s[1]) > MOVE || abs(want[2] - s[2]) > MOVE
        if (moved && nowMs - setAt >= SET_EVERY_MS) {
            active = true; sent = want.copyOf(); setAt = nowMs; keepAt = nowMs
            if (s != null && s[0] != want[0]) route = emptyList()
            return listOf(GO) + mapDigits(want[0].toInt()) + RouteWaypoint.digits(want[1]) + RouteWaypoint.digits(want[2])
        }
        if (active && nowMs - keepAt >= KEEP_EVERY_MS) { keepAt = nowMs; return listOf(KEEP) }
        return emptyList()
    }

    /** the add-on's answer ({"type":"pnav"}): a refused GO is not tried again for a while, and says why */
    fun onAnswer(op: String, ok: Boolean, why: String?, have: Boolean, nowMs: Long) {
        if (op == "go") {
            if (ok) this.why = null
            else {
                active = false; sent = null; route = emptyList(); refusedUntil = nowMs + REFUSED_MS
                this.why = if (!have) "Polestar is not running" else "Polestar refused${why?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""}"
            }
        }
        if (op == "cancel") { active = false; sent = null; route = emptyList(); liveFrom = 1 }
    }

    /** one leg of Polestar's route: its kind ("walk", "flight", ...), its continent (null = unknown), world points (x, y pairs) */
    class Leg(val kind: String, val cont: Int?, val pts: DoubleArray)
    var route: List<Leg> = emptyList(); private set
    var routeAt = Long.MIN_VALUE / 2; private set

    /** a {"type":"pnavroute"} document: legs {k, c, p = [wx, wy, ...]} */
    fun onRoute(d: JSONObject, nowMs: Long) {
        val a = d.optJSONArray("legs") ?: return
        val out = ArrayList<Leg>()
        for (i in 0 until a.length()) {
            val l = a.optJSONObject(i) ?: continue
            val p = l.optJSONArray("p") ?: continue
            if (p.length() < 4) continue
            val pts = DoubleArray(p.length() / 2 * 2) { p.optDouble(it) }
            if (pts.any { it.isNaN() }) continue
            out.add(Leg(l.optString("k", "walk"), if (l.has("c")) l.optInt("c") else null, pts))
        }
        if (!active) return                  // a late route after a cancel: not ours any more
        // a new plan starts the progress again; the 10 s resends of the same plan keep it
        val same = route.size == out.size && route.zip(out).all { (a, b) -> a.kind == b.kind && a.pts.contentEquals(b.pts) }
        if (!same) liveFrom = 1
        route = out; routeAt = nowMs
    }

    // ---- THE PATH STARTS AT THE PLAYER (ps2; the user, 2026-11-11: "the polestar path tends to be very slow to update and so very often
    // it looks like I have to back track once I start following the path"). Polestar plans a path ONCE from where the player stood and
    // only moves its own private index as they walk (Walk.lua), so its geometry's first point stays behind them; its own maps cut the
    // walk being walked at the player (RouteStrokes.lua Strokes.Live, RouteLines LIVE_REACH 60). The same here: the first leg starts
    // at the nearest point of it ahead of the player, and that progress only moves forward until a new plan comes.
    private var liveFrom = 1

    /** [liveRoute] with its first leg starting at the nearest point ahead of the player ([wx], [wy]: world yards, [cont] continent) */
    fun liveRouteFrom(nowMs: Long, wx: Double, wy: Double, cont: Int?): List<Leg>? {
        val legs = liveRoute(nowMs) ?: return null
        val f = legs[0]
        if (wx.isNaN() || wy.isNaN() || (f.cont != null && cont != null && f.cont != cont)) return legs
        val hit = trimAt(f.pts, liveFrom, wx, wy, LIVE_REACH_YD)?.also { liveFrom = it.first }
            ?: trimAt(f.pts, liveFrom, wx, wy, Double.MAX_VALUE)        // off the path: its nearest point ahead (Polestar re-plans past 25 yd)
            ?: return legs
        return listOf(Leg(f.kind, f.cont, hit.second)) + legs.drop(1)
    }

    /** the route when it is current: Polestar sends it every 10 s at least while guiding */
    fun liveRoute(nowMs: Long): List<Leg>? = route.takeIf { active && it.isNotEmpty() && nowMs - routeAt <= STALE_MS }

    companion object {
        /** Polestar's own LIVE_REACH (RouteLines.lua): progress along the path moves only while the player is this near it */
        const val LIVE_REACH_YD = 60.0

        /**
         * Pure: [pts] (x, y pairs) cut at the nearest point to ([wx], [wy]) on its segments from segment [from] (1 = the first) on,
         * within [reach] yards: (the segment's index, the points from that nearest point on), or null when none is that near
         */
        fun trimAt(pts: DoubleArray, from: Int, wx: Double, wy: Double, reach: Double): Pair<Int, DoubleArray>? {
            val n = pts.size / 2
            if (n < 2) return null
            var best = if (reach == Double.MAX_VALUE) Double.MAX_VALUE else reach * reach
            var bi = -1; var bx = 0.0; var by = 0.0
            for (i in maxOf(1, from) until n) {
                val ax = pts[2 * i - 2]; val ay = pts[2 * i - 1]
                val dx = pts[2 * i] - ax; val dy = pts[2 * i + 1] - ay
                val l2 = dx * dx + dy * dy
                val t = if (l2 > 0) (((wx - ax) * dx + (wy - ay) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
                val nx = ax + t * dx; val ny = ay + t * dy
                val d2 = (wx - nx) * (wx - nx) + (wy - ny) * (wy - ny)
                if (d2 <= best) { best = d2; bi = i; bx = nx; by = ny }
            }
            if (bi < 0) return null
            val out = DoubleArray((n - bi + 1) * 2)
            out[0] = bx; out[1] = by
            System.arraycopy(pts, bi * 2, out, 2, (n - bi) * 2)
            return bi to out
        }

        const val GO = 36; const val KEEP = 37; const val CANCEL = 39
        const val ROW = "pnav"
        const val MOVE = 0.004
        const val SET_EVERY_MS = 2_000L
        const val KEEP_EVERY_MS = 20_000L      // well inside Map.lua's 60 s KEEP_S
        const val REFUSED_MS = 60_000L
        const val STALE_MS = 25_000L

        /** a uiMapID as three base-36 digits (Map.lua: (d1 * 36 + d2) * 36 + d3) */
        fun mapDigits(m: Int): List<Int> {
            val n = m.coerceIn(0, 36 * 36 * 36 - 1)
            return listOf(n / 1296, n / 36 % 36, n % 36)
        }
    }
}

/**
 * ps4 (the user, 2026-11-11: "My current polestar objective does not give any direction on where to go"): the add-on is told whether
 * the light blue Polestar row is picked (Map.lua WC.pnav.picked; PadMap m132 puts an item-started step's item on Y only then) - the
 * "pnav" row's 38 then 8 (picked) / 9 (not): at every change, and again every [AGAIN_MS] while picked (the add-on forgets it after 75 s).
 * Pure: which taps, at [nowMs].
 */
class PolestarPickTell {
    private var told: Boolean? = null
    private var toldAt = Long.MIN_VALUE / 2
    fun tick(picked: Boolean, nowMs: Long): List<Int> {
        if (told == picked && (!picked || nowMs - toldAt < AGAIN_MS)) return emptyList()
        told = picked; toldAt = nowMs
        return listOf(PolestarQol.COL_NEXT, if (picked) PICKED else RELEASED)
    }
    /** a refused / lost batch: said again at the next tick */
    fun lost() { told = null }

    companion object {
        const val PICKED = 8; const val RELEASED = 9
        const val AGAIN_MS = 30_000L
    }
}
