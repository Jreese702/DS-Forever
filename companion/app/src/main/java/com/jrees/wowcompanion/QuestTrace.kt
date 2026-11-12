package com.jrees.wowcompanion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The areas of Forever's OWN quests, traced from the user's play.
 *
 * No offline source has them (the server sends the shape to the client and no call hands it over),
 * but the client answers C_Minimap.IsInsideQuestBlob(questID). The addon samples it while the
 * player walks (Map.lua traceSample: every 4 yd, at most twice a second, never in combat) and each
 * position update carries `qb` = [[questID, 1|0], ...] at the player's world position. User
 * 2026-09-29 14:40: "that sounds really really good actually".
 *
 * The game draws an area as the convex hull of its points, so the traced shape is the convex hull
 * of every INSIDE sample plus the midpoint of every inside/outside flip between two samples at most
 * EDGE_YD apart (the edge lies between them). It only ever grows toward the real shape, and since
 * hull(hull + new) = hull(all), only the hull is kept: a few dozen points per quest, saved in the
 * app's own files (quest_trace.json), so the shape carries across sessions.
 * A hull thinner than MIN_AREA_YD2 (a straight walk) is not drawn yet; the circle stays until then.
 *
 * t2 (the user, 2026-11-12, "Testing the Wells": "why is it showing this long narrow blue path as the quest area for the current
 * objective???"): ONE hull per quest joined a quest's separate blobs (Jansen Stead's well and Molsen Farm's, hundreds of yards
 * apart) into one long strip across the land between them. Now the INSIDE samples themselves are kept (snapped to a GRID_YD grid,
 * at most MAX_SAMPLES), samples within JOIN_YD of each other (chained) form one area, and each area is its own hull. An area thinner
 * than MIN_WIDTH_YD or longer than MAX_ASPECT times its width (a walk along a road, two far spots) is never drawn. A hull saved
 * before t2 (no samples) is kept only when it is not such a strip; new play rebuilds it from samples.
 */
object QuestTrace {
    private const val EDGE_YD = 12.0
    private const val MIN_AREA_YD2 = 150.0
    private const val SAVE_EVERY_MS = 10_000L
    private const val GRID_YD = 4.0
    private const val JOIN_YD = 30.0
    private const val MAX_SAMPLES = 1500
    private const val MIN_WIDTH_YD = 10.0
    private const val MAX_ASPECT = 5.0

    private class T(var cont: Int) {
        var pts: List<DoubleArray> = emptyList()      // the hull (or the first one or two points)
        var area = 0.0
        var lastX = Double.NaN
        var lastY = Double.NaN
        var lastIn = -1
        /** t2: the inside samples (grid-snapped, keyed by cell); empty for a hull saved before t2 */
        val samples = LinkedHashMap<Long, DoubleArray>()
        /** t2: the drawn areas, rebuilt when the samples change */
        var shapes: List<List<DoubleArray>>? = null
    }
    private val traces = HashMap<Int, T>()
    private var file: File? = null
    private var dirty = false
    private var savedAt = 0L

    fun init(c: Context) {
        if (file != null) return
        val f = File(c.applicationContext.filesDir, "quest_trace.json")
        file = f
        try {
            if (!f.canRead()) return
            val q = JSONObject(f.readText()).optJSONObject("q") ?: return
            for (k in q.keys()) {
                val o = q.optJSONObject(k) ?: continue
                val a = o.optJSONArray("p") ?: continue
                val t = T(o.optInt("c", -1))
                val pts = ArrayList<DoubleArray>()
                var i = 0
                while (i + 1 < a.length()) { pts.add(doubleArrayOf(a.optDouble(i), a.optDouble(i + 1))); i += 2 }
                t.pts = hull(pts); t.area = area(t.pts)
                o.optJSONArray("s")?.let { s ->
                    var j = 0
                    while (j + 1 < s.length()) { addSample(t, s.optDouble(j), s.optDouble(j + 1)); j += 2 }
                }
                traces[k.toInt()] = t
            }
        } catch (_: Throwable) { }
    }

    /** One sample row list at world ([wx], [wy]) on continent [cont]. True when a drawn shape changed. */
    fun absorb(qb: JSONArray, wx: Double, wy: Double, cont: Int): Boolean {
        var changed = false
        for (i in 0 until qb.length()) {
            val row = qb.optJSONArray(i) ?: continue
            val q = row.optInt(0, 0)
            if (q <= 0) continue
            val inside = row.optInt(1, 0) == 1
            var t = traces[q]
            if (t == null || t.cont != cont) { t = T(cont); traces[q] = t }
            val near = !t.lastX.isNaN() && hypot(wx - t.lastX, wy - t.lastY) <= EDGE_YD
            val add = ArrayList<DoubleArray>(2)
            if (inside) add.add(doubleArrayOf(wx, wy))
            if (near && t.lastIn >= 0 && (t.lastIn == 1) != inside)
                add.add(doubleArrayOf((wx + t.lastX) / 2, (wy + t.lastY) / 2))
            t.lastX = wx; t.lastY = wy; t.lastIn = if (inside) 1 else 0
            if (add.isEmpty()) continue
            for (p in add) if (addSample(t, p[0], p[1])) { t.shapes = null; changed = true; dirty = true }
            val wasDrawn = t.area >= MIN_AREA_YD2
            val before = t.pts
            t.pts = hull(before + add)
            t.area = area(t.pts)
            if (t.pts.size != before.size || t.pts.indices.any { t.pts[it] !== before.getOrNull(it) }) {
                dirty = true
                if (t.area >= MIN_AREA_YD2 || wasDrawn) changed = true
            }
        }
        return changed
    }

    /** t2: the traced areas of quest [q] on continent [cont], world coords (one hull per separate area), empty while none is drawable */
    fun shapes(q: Int, cont: Int): List<List<DoubleArray>> {
        val t = traces[q] ?: return emptyList()
        if (t.cont != cont) return emptyList()
        t.shapes?.let { return it }
        val out = if (t.samples.isEmpty()) {
            // a hull saved before t2: only when it is a real area, never a strip
            if (t.pts.size >= 3 && t.area >= MIN_AREA_YD2 && drawable(t.pts)) listOf(t.pts) else emptyList()
        } else areas(t.samples.values.toList())
        t.shapes = out
        return out
    }

    /** t2: [pts] grouped (chained within JOIN_YD), each group's hull, only the drawable ones, the biggest first */
    internal fun areas(pts: List<DoubleArray>): List<List<DoubleArray>> {
        if (pts.isEmpty()) return emptyList()
        val parent = IntArray(pts.size) { it }
        fun root(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; var k = i; while (parent[k] != r) { val n = parent[k]; parent[k] = r; k = n }; return r }
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            if (hypot(pts[i][0] - pts[j][0], pts[i][1] - pts[j][1]) <= JOIN_YD) { val a = root(i); val b = root(j); if (a != b) parent[a] = b }
        }
        return pts.indices.groupBy { root(it) }.values
            .map { g -> hull(g.map { pts[it] }) }
            .filter { it.size >= 3 && area(it) >= MIN_AREA_YD2 && drawable(it) }
            .sortedByDescending { area(it) }
    }

    /** t2: a hull at least MIN_WIDTH_YD wide and at most MAX_ASPECT times as long as it is wide */
    internal fun drawable(h: List<DoubleArray>): Boolean {
        if (h.size < 3) return false
        var len = 0.0
        for (i in h.indices) for (j in i + 1 until h.size) len = maxOf(len, hypot(h[i][0] - h[j][0], h[i][1] - h[j][1]))
        // the least width of a convex polygon: over its edges, the farthest vertex from that edge's line
        var width = Double.MAX_VALUE
        for (i in h.indices) {
            val a = h[i]; val b = h[(i + 1) % h.size]
            val el = hypot(b[0] - a[0], b[1] - a[1])
            if (el <= 1e-9) continue
            var far = 0.0
            for (p in h) far = maxOf(far, abs((b[0] - a[0]) * (a[1] - p[1]) - (a[0] - p[0]) * (b[1] - a[1])) / el)
            width = minOf(width, far)
        }
        if (width == Double.MAX_VALUE) return false
        return width >= MIN_WIDTH_YD && len <= MAX_ASPECT * width
    }

    /** t2: one inside sample, snapped to the grid; true when it is new */
    private fun addSample(t: T, x: Double, y: Double): Boolean {
        val gx = Math.round(x / GRID_YD); val gy = Math.round(y / GRID_YD)
        val key = (gx shl 32) xor (gy and 0xffffffffL)
        if (t.samples.containsKey(key)) return false
        if (t.samples.size >= MAX_SAMPLES) t.samples.remove(t.samples.keys.first())
        t.samples[key] = doubleArrayOf(gx * GRID_YD, gy * GRID_YD)
        return true
    }

    fun saveIfDue() {
        val f = file ?: return
        val now = System.currentTimeMillis()
        if (!dirty || now - savedAt < SAVE_EVERY_MS) return
        savedAt = now; dirty = false
        try {
            val q = JSONObject()
            for ((k, t) in traces) {
                if (t.pts.isEmpty() && t.samples.isEmpty()) continue
                val a = JSONArray()
                for (p in t.pts) { a.put(Math.round(p[0] * 10) / 10.0); a.put(Math.round(p[1] * 10) / 10.0) }
                val s = JSONArray()
                for (p in t.samples.values) { s.put(p[0]); s.put(p[1]) }
                q.put(k.toString(), JSONObject().put("c", t.cont).put("p", a).put("s", s))
            }
            val tmp = File(f.path + ".tmp")
            tmp.writeText(JSONObject().put("q", q).toString())
            tmp.renameTo(f)
        } catch (_: Throwable) { dirty = true }
    }

    // Andrew's monotone chain; keeps the input objects, so an unchanged hull compares identical
    private fun hull(pts: List<DoubleArray>): List<DoubleArray> {
        if (pts.size < 3) return pts
        val s = pts.sortedWith(compareBy({ it[0] }, { it[1] }))
        fun cross(o: DoubleArray, a: DoubleArray, b: DoubleArray) =
            (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        val lo = ArrayList<DoubleArray>(); val hi = ArrayList<DoubleArray>()
        for (p in s) {
            while (lo.size >= 2 && cross(lo[lo.size - 2], lo[lo.size - 1], p) <= 0) lo.removeAt(lo.size - 1)
            lo.add(p)
        }
        for (p in s.asReversed()) {
            while (hi.size >= 2 && cross(hi[hi.size - 2], hi[hi.size - 1], p) <= 0) hi.removeAt(hi.size - 1)
            hi.add(p)
        }
        lo.removeAt(lo.size - 1); hi.removeAt(hi.size - 1)
        val h = lo + hi
        return if (h.size >= 3) h else listOf(s.first(), s.last())   // a straight walk: its two ends
    }

    private fun area(p: List<DoubleArray>): Double {
        if (p.size < 3) return 0.0
        var s = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a[0] * b[1] - b[0] * a[1] }
        return abs(s) / 2
    }
}
