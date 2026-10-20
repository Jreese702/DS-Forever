package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * THE LEARNED AGGRO RADIUS (the live aggro layer; E:/safepath-work/docs/FSR_NOTES.md 2.4 - 2.5, the contract's "pl" pull events).
 * The fit is adapted from Forever Simple Range's Aggro.lua (its notice below, as its MIT licence asks).
 *
 * Each pull the add-on reports is an interval [lo, hi] (yd) the mob's radius lay in; with diff = mob level - player level it becomes
 * "the equal-level base was in [lo - diff, hi - diff]". The fit is FSR's MAXIMUM-OVERLAP VOTE, not a mean or a running median: the
 * region covered by the most intervals wins (a tie: the one nearest the prior), and the base is the prior moved just far enough to sit
 * inside it - consistent pulls narrow it, outliers are outvoted. Tiers, the most specific that has pulls: this npc -> its creature type
 * (the plate's `ct`) -> all pulls -> [DEFAULT_BASE]. The radius is then clamp(base + level - playerLevel, 5, 45) ([LiveDanger.radius]).
 *
 * Stored in the app's own files ([init]: aggro_pulls.json; the add-on sends each pull once), [MAX_RECORDS] kept, the oldest dropped.
 * An unreadable or invalid file PAUSES the learning and is left untouched (FSR's rule). Thread-safe.
 */
/*
 * Forever Simple Range - Copyright (c) 2026 Tarolg
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files
 * (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge,
 * publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
 * ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH
 * THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
object AggroFit {
    const val DEFAULT_BASE = 20.0
    const val MAX_RECORDS = 500
    /** the add-on already rejects these (FSR's Record); checked again so a bad line never teaches */
    const val MAX_WIDTH = 15.0

    class Rec(val n: Int?, val ct: Int?, val lv: Int, val plv: Int, val lo: Double, val hi: Double)
    /** a tier's fit: [base] (yd at equal level), [pulls] its records, [agree] the winning overlap, [low] .. [high] the winning region */
    class Fit(val base: Double, val pulls: Int, val agree: Int, val low: Double, val high: Double)

    private val recs = ArrayList<Rec>()
    private var file: File? = null
    @Volatile var paused = false; private set
    private val seen = LinkedHashSet<String>()
    private var model: Model? = null

    /** read the stored pulls (once; [f] the app's aggro_pulls.json) */
    @Synchronized fun init(f: File) {
        if (file == f) return
        file = f; recs.clear(); model = null; paused = false
        if (!f.isFile) return
        try {
            val o = JSONObject(f.readText())
            if (o.optInt("schema", 0) != 1) { paused = true; return }
            val a = o.getJSONArray("records")
            val read = ArrayList<Rec>()
            for (i in 0 until a.length()) {
                val r = a.getJSONObject(i)
                val lo = r.getDouble("lo"); val hi = r.getDouble("hi")
                if (!lo.isFinite() || !hi.isFinite() || lo > hi) { paused = true; return }
                read.add(Rec(r.optInt("n", 0).takeIf { it > 0 }, if (r.has("ct")) r.getInt("ct") else null, r.getInt("lv"), r.getInt("plv"), lo, hi))
            }
            recs.addAll(read.takeLast(MAX_RECORDS))
        } catch (t: Throwable) { paused = true }
    }

    /** off-device: forget everything (no file) */
    @Synchronized fun reset() { recs.clear(); file = null; model = null; paused = false; seen.clear() }

    /** a pull event from a full record; false = not learned (paused, seen already, or rejected) */
    @Synchronized fun add(p: AggroDoc.Pull): Boolean {
        if (paused) return false
        val key = "${p.s}:${p.n}:${p.t}:${p.lo}:${p.hi}"
        if (!seen.add(key)) return false
        while (seen.size > 256) seen.remove(seen.first())
        if (!valid(p.lv, p.plv, p.lo, p.hi)) return false
        recs.add(Rec(p.n, p.ct, p.lv, p.plv, p.lo, p.hi))
        while (recs.size > MAX_RECORDS) recs.removeAt(0)
        model = null
        save()
        return true
    }

    /** FSR's rejects the app can see: a skull or unknown level, lo > hi, too wide, a default radius near the clamps */
    fun valid(lv: Int, plv: Int, lo: Double, hi: Double): Boolean {
        if (lv <= 0 || plv <= 0 || !lo.isFinite() || !hi.isFinite() || lo > hi || hi - lo > MAX_WIDTH) return false
        val def = DEFAULT_BASE + lv - plv
        return def > 10.0 && def < 40.0
    }

    /** the equal-level base for a mob (npc [n], creature type [ct]): the most specific tier with pulls, else [DEFAULT_BASE] */
    @Synchronized fun base(n: Int?, ct: Int?): Double {
        val m = model ?: build().also { model = it }
        n?.let { m.npcs[it]?.let { f -> return f.base } }
        ct?.let { m.types[it]?.let { f -> return f.base } }
        return m.all?.base ?: DEFAULT_BASE
    }

    @Synchronized fun count() = recs.size

    private class Model(val all: Fit?, val types: Map<Int, Fit>, val npcs: Map<Int, Fit>)
    private fun build(): Model {
        fun iv(r: Rec): Pair<Double, Double> { val diff = (r.lv - r.plv).toDouble(); return (r.lo - diff) to (r.hi - diff) }
        val all = if (recs.isEmpty()) null else fit(recs.map(::iv), DEFAULT_BASE)
        val prior0 = all?.base ?: DEFAULT_BASE
        val types = recs.filter { it.ct != null }.groupBy { it.ct!! }.mapValues { (_, rs) -> fit(rs.map(::iv), prior0) }
        val npcs = recs.filter { it.n != null }.groupBy { it.n!! }.mapValues { (_, rs) ->
            val ct = rs.firstNotNullOfOrNull { it.ct }
            fit(rs.map(::iv), ct?.let { types[it]?.base } ?: prior0) }
        return Model(all, types, npcs)
    }

    /**
     * FSR's Fit: the sweep over the sorted endpoints (a start before an end at the same value: closed intervals), the region the most
     * intervals cover (a tie: the one nearest [prior]), the base = [prior] clamped into it
     */
    fun fit(intervals: List<Pair<Double, Double>>, prior: Double): Fit {
        if (intervals.isEmpty()) return Fit(prior, 0, 0, prior, prior)
        // (value, +1 start / -1 end), starts first at equal values
        val ev = intervals.flatMap { (a, b) -> listOf(a to 1, b to -1) }.sortedWith(compareBy<Pair<Double, Int>> { it.first }.thenBy { -it.second })
        var cur = 0; var best = -1; var bLo = prior; var bHi = prior; var bDist = Double.MAX_VALUE
        for (i in ev.indices) {
            cur += ev[i].second
            if (ev[i].second != 1) continue
            // a region starts here and runs to the next endpoint
            val lo = ev[i].first; val hi = ev.getOrNull(i + 1)?.first ?: lo
            if (i + 1 < ev.size && ev[i + 1].second == 1 && ev[i + 1].first == lo) continue   // more starts at the same value
            val dist = if (prior < lo) lo - prior else if (prior > hi) prior - hi else 0.0
            if (cur > best || (cur == best && dist < bDist)) { best = cur; bLo = lo; bHi = hi; bDist = dist }
        }
        return Fit(prior.coerceIn(bLo, bHi), intervals.size, best, bLo, bHi)
    }

    private fun save() {
        val f = file ?: return
        try {
            val a = JSONArray()
            for (r in recs) a.put(JSONObject().apply { r.n?.let { put("n", it) }; r.ct?.let { put("ct", it) }
                put("lv", r.lv); put("plv", r.plv); put("lo", r.lo); put("hi", r.hi) })
            val tmp = File(f.path + ".tmp")
            tmp.writeText(JSONObject().put("schema", 1).put("records", a).toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Throwable) { }
    }
}
