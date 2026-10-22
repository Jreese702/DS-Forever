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
    /**
     * THE SLOPE BELOW THE PLAYER'S LEVEL (the user, 2026-10-22: "Fit it"; test58's ten ok pulls, mobs 8-10 against a player 13-14):
     * World of Warcraft: Forever's radius falls faster under the player's level than classic's yard a level - measured 0..5 to 15..20
     * where classic says 15-17. FSR's vote over k = (base - r) / (player level - mob level) put 6 of the 10 at k = 2.22 (0..5, 15..20
     * and 30..35 outvoted: a social pull, a link). The prior, bounded [K_MIN, K_MAX]; the device's own pulls under the player's level move
     * it ([kBelow]). At and above the player's level classic's yard a level stands (no data there yet)
     */
    const val K_BELOW_PRIOR = 2.2
    const val K_MIN = 1.0
    const val K_MAX = 3.5
    /** the equal-level base's bounds whatever the pulls say (test70: 4 near-level pulls had moved it to 11 yd) */
    const val BASE_MIN = 15.0
    const val BASE_MAX = 26.0
    /** a tier (all, a creature type, an npc; the slope) moves off its prior only with this many pulls - a few social pulls or links
     *  must not teach it (test70's base 11 from 4 pulls) */
    const val MIN_PULLS = 8
    /** pulls this near the player's level (and above) teach the base; those [BELOW] levels or more under it teach the slope */
    const val NEAR = -1
    const val BELOW = -2
    const val SKULL_PLUS = 10
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
        val b = n?.let { m.npcs[it]?.base } ?: ct?.let { m.types[it]?.base } ?: m.all?.base ?: DEFAULT_BASE
        return b.coerceIn(BASE_MIN, BASE_MAX)
    }

    /** the yards a level under the player's the radius falls ([K_BELOW_PRIOR], moved by the pulls under the player's level) */
    @Synchronized fun kBelow(): Double = (model ?: build().also { model = it }).k

    /**
     * THE SHARED RADIUS RULE (the bottom map's rings, the route's live discs; the contract's - Interact mirrors the constants): a mob of
     * level [lm] (-1 a skull: the player's level + [SKULL_PLUS]; null unknown: [bracket]'s midpoint, else the player's level) against a
     * player of [lp]: base + diff at or above the player's level, base + diff x [kBelow] under it, clamped 5..45; base the learned one
     * for npc [n] / type [ct]
     */
    fun radius(lm: Int?, lp: Int, n: Int? = null, ct: Int? = null, bracket: IntRange? = null): Double {
        val l = when {
            lm == -1 -> lp + SKULL_PLUS
            lm != null -> lm
            bracket != null -> (bracket.first + bracket.last + 1) / 2
            else -> lp
        }
        val d = (l - lp).toDouble()
        val r = base(n, ct) + if (d >= 0) d else d * kBelow()
        return r.coerceIn(5.0, 45.0)
    }

    @Synchronized fun count() = recs.size

    private class Model(val all: Fit?, val types: Map<Int, Fit>, val npcs: Map<Int, Fit>, val k: Double, val kFit: Fit?)
    private fun build(): Model {
        // the BASE from the pulls at or near the player's level (classic's yard a level there); the SLOPE from those well under it
        val near = recs.filter { it.lv - it.plv >= NEAR }
        fun iv(r: Rec): Pair<Double, Double> { val diff = (r.lv - r.plv).toDouble(); return (r.lo - diff) to (r.hi - diff) }
        val all = if (near.size < MIN_PULLS) null else fit(near.map(::iv), DEFAULT_BASE)
        val prior0 = all?.base ?: DEFAULT_BASE
        val types = near.filter { it.ct != null }.groupBy { it.ct!! }.filterValues { it.size >= MIN_PULLS }.mapValues { (_, rs) -> fit(rs.map(::iv), prior0) }
        val npcs = near.filter { it.n != null }.groupBy { it.n!! }.filterValues { it.size >= MIN_PULLS }.mapValues { (_, rs) ->
            val ct = rs.firstNotNullOfOrNull { it.ct }
            fit(rs.map(::iv), ct?.let { types[it]?.base } ?: prior0) }
        // k: each pull under the player's level, r in [lo, hi] at r = b + diff x k (b its tier's base): k in [(b - hi) / -diff, (b - lo) / -diff]
        val below = recs.filter { it.lv - it.plv <= BELOW }
        val kFit = if (below.size < MIN_PULLS) null else fit(below.map { r ->
            val b = (r.n?.let { npcs[it]?.base } ?: r.ct?.let { types[it]?.base } ?: prior0).coerceIn(BASE_MIN, BASE_MAX)
            val dd = (r.plv - r.lv).toDouble(); (b - r.hi) / dd to (b - r.lo) / dd }, K_BELOW_PRIOR)
        return Model(all, types, npcs, (kFit?.base ?: K_BELOW_PRIOR).coerceIn(K_MIN, K_MAX), kFit)
    }

    /** the fit's words for the log: the base, the slope under the player's level, and how many pulls taught each */
    @Synchronized fun words(): String {
        val m = model ?: build().also { model = it }
        return "aggro fit: base ${"%.1f".format(m.all?.base ?: DEFAULT_BASE)} yd (${m.all?.pulls ?: 0} pulls near level), " +
            "${"%.2f".format(m.k)} yd a level under (${m.kFit?.pulls ?: 0} pulls; prior $K_BELOW_PRIOR)"
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
