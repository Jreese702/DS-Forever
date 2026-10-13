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
 */
object QuestTrace {
    private const val EDGE_YD = 12.0
    private const val MIN_AREA_YD2 = 150.0
    private const val SAVE_EVERY_MS = 10_000L

    private class T(var cont: Int) {
        var pts: List<DoubleArray> = emptyList()      // the hull (or the first one or two points)
        var area = 0.0
        var lastX = Double.NaN
        var lastY = Double.NaN
        var lastIn = -1
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

    /** The traced shape of quest [q] on continent [cont], world coords, or null while too small. */
    fun shape(q: Int, cont: Int): List<DoubleArray>? {
        val t = traces[q] ?: return null
        if (t.cont != cont || t.pts.size < 3 || t.area < MIN_AREA_YD2) return null
        return t.pts
    }

    fun saveIfDue() {
        val f = file ?: return
        val now = System.currentTimeMillis()
        if (!dirty || now - savedAt < SAVE_EVERY_MS) return
        savedAt = now; dirty = false
        try {
            val q = JSONObject()
            for ((k, t) in traces) {
                if (t.pts.isEmpty()) continue
                val a = JSONArray()
                for (p in t.pts) { a.put(Math.round(p[0] * 10) / 10.0); a.put(Math.round(p[1] * 10) / 10.0) }
                q.put(k.toString(), JSONObject().put("c", t.cont).put("p", a))
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
