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
            active = false; sent = null; route = emptyList()
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
        if (op == "cancel") { active = false; sent = null; route = emptyList() }
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
        route = out; routeAt = nowMs
    }

    /** the route when it is current: Polestar sends it every 10 s at least while guiding */
    fun liveRoute(nowMs: Long): List<Leg>? = route.takeIf { active && it.isNotEmpty() && nowMs - routeAt <= STALE_MS }

    companion object {
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
