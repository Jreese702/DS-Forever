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

    // ---- THE ROW HOLDS (pn4; the user, 2026-11-11: "Okay now I actually do have a polestar objective that is not in my bag and not
    // telling me where to go", then: Polestar's own arrow stays hidden, DS Forever's compass leads). The add-on is TOLD while the light
    // blue Polestar row holds (38, 8; 38, 9 when it stops; re-said every [KEEP_EVERY_MS], Map.lua forgets it after its KEEP_S): its
    // compass then shows Polestar's waypoint and route whether or not a quest is focused or in the log, and Polestar's route is read
    // even when Polestar guides its step itself (it drops our GO at every step change) - so the route here is kept while [psOn] too.
    /** the light blue row holds (the last [tick]'s word) */
    // int/1429: this word is ALSO ps-noloc-guide's "row picked" (PadMap's starter item on Y reads it through Map.lua WC.pnav.psOn):
    // ONE protocol (pnav 38 then 8 / 9), ONE sender (this tick; PolestarPickTell folded in), the strictest timing of the two (on
    // change + every 20 s; the add-on forgets it after 60 s) and PickTell's re-say after a lost batch ([lost])
    var psOn = false; private set
    private var psSaid: Boolean? = false                // null = said into a lost batch: say again at the next tick
    private var psSaidAt = Long.MIN_VALUE / 2

    /** a refused / lost tap batch: the row's word is said again at the next [tick] (ps-noloc-guide's PolestarPickTell.lost) */
    fun lost() { psSaid = null }

    /** One look at the goal at [nowMs]: [want] = (uiMapID, x, y) or null; [ps] = the light blue Polestar row holds. The row's columns
     *  to tap, in order (empty = nothing): the GO / KEEP / CANCEL first, then the row's word (never inside a GO's digits) */
    fun tick(want: DoubleArray?, nowMs: Long, ps: Boolean = false): List<Int> {
        val nav = navTick(want, nowMs)
        psOn = ps
        if (!ps && !active) route = emptyList()
        val say = ps != psSaid || (ps && nowMs - psSaidAt >= KEEP_EVERY_MS)
        if (!say) return nav
        psSaid = ps; psSaidAt = nowMs
        return nav + listOf(PS_SAY, if (ps) PS_ON else PS_OFF)
    }

    private fun navTick(want: DoubleArray?, nowMs: Long): List<Int> {
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
        if (!active && !psOn) return         // a late route after a cancel: not ours any more (pn4: the held row's route is)
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
    fun liveRoute(nowMs: Long): List<Leg>? = route.takeIf { (active || psOn) && it.isNotEmpty() && nowMs - routeAt <= STALE_MS }

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
        /** pn4: 38 then 8 = the light blue row holds, 9 = it does not (38's 0-7 are Polestar's automation mask, PolestarQol) */
        const val PS_SAY = 38; const val PS_ON = 8; const val PS_OFF = 9
        const val ROW = "pnav"
        const val MOVE = 0.004
        const val SET_EVERY_MS = 2_000L
        const val KEEP_EVERY_MS = 20_000L      // well inside Map.lua's 60 s KEEP_S
        const val REFUSED_MS = 60_000L
        const val STALE_MS = 25_000L

        /**
         * pn4 (the user, 2026-11-11: "a polestar objective that is not in my bag and not telling me where to go"): where the maps mark
         * Polestar's goal - the held light blue row's waypoint (this map's 0..1) when it has one on the map shown ([mapId]) and no quest
         * is focused through it ([focusThrough] 0: that quest's own disc marks it); on another map of the same continent, carried
         * through its world spot and [rect] (ps4); else null
         */
        fun goalMark(holds: Boolean, step: PolestarStep?, mapId: Int, focusThrough: Int, rect: PoiStore.Rect? = null): DoubleArray? {
            if (!holds || step == null || !step.hasLoc || mapId == 0 || focusThrough != 0) return null
            if (step.map == mapId) return doubleArrayOf(step.x, step.y)
            // ps4 (the review of pn4: a goto / talk step across a zone line had no disc and no rim pin on Home's small map): a step on
            // another map of the shown map's continent, through its world spot and the shown map's world rect (as the route's legs)
            // - off the field, so the small map pins it at the rim on the way there
            val r = rect ?: return null
            if (!step.hasWorld || !r.valid || step.cont != r.continent) return null
            return doubleArrayOf(r.nx(step.wy), r.ny(step.wx))
        }

        /** a uiMapID as three base-36 digits (Map.lua: (d1 * 36 + d2) * 36 + d3) */
        fun mapDigits(m: Int): List<Int> {
            val n = m.coerceIn(0, 36 * 36 * 36 - 1)
            return listOf(n / 1296, n / 36 % 36, n % 36)
        }
    }
}

