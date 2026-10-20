package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * THE "AGGRO" DOCUMENTS, READ (the live aggro layer, the user 2026-10-19: "My thing didn't account for the mobs that are randomly
 * between me and where I am going"; E:/safepath-work/docs/AGGRO_DOC_CONTRACT_v1.md, v1, the parser follows it exactly). Pure: a
 * JSONObject in, a record out; no Android.
 *
 * - the record rides the pos doc (`map.ag`) while moving, or comes alone as {"type":"aggro", ...record} while still;
 * - a FULL record (`q`, `me`, `m`: every plate now, at most 24, a slot missing from `m` = its plate gone), a SAME record ("same":1:
 *   nothing changed since full record `q`), an OFF record ("off":"inst"|"enc"|"pvp": the layer stops, clear everything);
 * - {"type":"aggrocov"}: the coverage (the nameplate cvars, the spell and item brackets);
 * - {"type":"aggroack"}: the pull / qmask answers (read here; their senders come later).
 *
 * The "ag1" additions (the contract's "Built: ag1"): per mob `br` (the add-on's world bearing) and `bs` (its yaw's source).
 *
 * A field the add-on did not send is UNKNOWN (null here); the contract never sends JSON null, and one that comes anyway counts as
 * unknown too. Booleans are 0/1.
 */
object AggroDoc {
    const val VERSION = 1
    const val SLOTS = 24

    /** the player in a full record: [x] north yd, [y] west yd, [f] facing (rad, 0 = north, counter-clockwise; null unknown) */
    class Me(val lv: Int?, val map: Int?, val x: Double, val y: Double, val f: Double?)

    /** one plate (the contract's per-mob table; null = omitted = unknown) */
    class Mob(val s: Int, val n: Int?, val ct: Int?, val lv: Int?, val c: String?, val r: Int?, val cb: Boolean?, val om: Int?,
              val a: Double?, val b: Double?, val rk: String?, val qi: Boolean?, val dd: Boolean, val td: Boolean?, val sx: Double?,
              val cl: Boolean?, val age: Double?, val mk: String?,
              /** ag1: the add-on's own world bearing to the mob (rad, as `me.f`), and where its yaw came from ("c" the compass's
               *  dead-reckoned camera, "f" the facing) */
              val br: Double? = null, val bs: String? = null) {
        /** reaction 1..3 (hostile, unfriendly): aggressive, gets a circle; 4 neutral: none (the user's ruling). Unknown counts as
         *  aggressive (a wrong circle costs a detour, a missing one a fight) */
        val aggressive get() = r == null || r in 1..3
        val skull get() = lv == -1
        /** elite, rare elite, boss */
        val elite get() = c == "e" || c == "R" || c == "b"
        /** the identity fields a slot keeps for its plate's life (a change = another plate in the slot) */
        fun sameMobAs(o: Mob) = (n == null || o.n == null || n == o.n) && (ct == null || o.ct == null || ct == o.ct)
    }

    /** a pull event (FSR's interval: [lo] the bracket's lower bound at the pull, [hi] the last calm reading's upper bound) */
    class Pull(val s: Int, val n: Int?, val ct: Int?, val lv: Int, val plv: Int, val lo: Double, val hi: Double, val t: Double)

    sealed class Record(val t: Double?)
    class Full(val q: Int, t: Double?, val comb: Boolean, val me: Me, val m: List<Mob>, val pl: List<Pull>) : Record(t)
    class Same(val q: Int, t: Double?) : Record(t)
    class Off(val why: String, t: Double?) : Record(t)

    /** {"type":"aggrocov"}: [cv] the nameplate cvars (strings: maxd, off, ena, vmax), [sp] / [it] (id, yd), [ci] CheckInteractDistance */
    class Coverage(val cv: Map<String, String>, val sp: List<Pair<Int, Double>>, val it: List<Pair<Int, Double>>, val ci: List<Double>) {
        /** the nameplate distance (yd), null unknown */
        val maxd get() = cv["maxd"]?.toDoubleOrNull()?.takeIf { it > 0.0 }
        /** offscreen nameplates on (null unknown) */
        val offscreen get() = cv["off"]?.let { it == "1" || it.equals("true", true) }
        /** enemy nameplates on (null unknown) */
        val enemies get() = cv["ena"]?.let { it == "1" || it.equals("true", true) }
    }

    /** {"type":"aggroack"}: op "pull" (k 1 | 2, s the slot or -1 cleared) or "qmask" (mask) */
    class Ack(val op: String, val k: Int?, val s: Int?, val ok: Boolean, val why: String?, val mask: Long?)

    /** the record riding a pos doc's map ("ag"), or null */
    fun fromPos(map: JSONObject?): Record? = map?.optJSONObject("ag")?.let { record(it) }

    /** a record (the pos doc's "ag" object, or a whole {"type":"aggro"} doc); null = not one this v reads */
    fun record(o: JSONObject): Record? {
        val v = int(o, "v")
        if (v != null && v != VERSION) return null
        val t = num(o, "t")
        str(o, "off")?.let { return Off(it, t) }
        val q = int(o, "q") ?: return null
        if (int(o, "same") == 1) return Same(q, t)
        val me = o.optJSONObject("me") ?: return null
        val x = num(me, "x") ?: return null; val y = num(me, "y") ?: return null
        val m = ArrayList<Mob>()
        val seen = HashSet<Int>()
        o.optJSONArray("m")?.let { a -> for (i in 0 until a.length()) mob(a.optJSONObject(i))?.let { if (seen.add(it.s)) m.add(it) } }
        val pl = ArrayList<Pull>()
        o.optJSONArray("pl")?.let { a -> for (i in 0 until a.length()) pull(a.optJSONObject(i))?.let { pl.add(it) } }
        return Full(q, t, int(o, "comb") == 1, Me(int(me, "lv"), int(me, "map"), x, y, num(me, "f")), m, pl)
    }

    private fun mob(o: JSONObject?): Mob? {
        o ?: return null
        val s = int(o, "s")?.takeIf { it in 0 until SLOTS } ?: return null
        return Mob(s, int(o, "n")?.takeIf { it > 0 }, int(o, "ct"), int(o, "lv"), str(o, "c"), int(o, "r")?.takeIf { it in 1..8 },
            bool(o, "cb"), int(o, "om"), num(o, "a")?.takeIf { it >= 0.0 }, num(o, "b")?.takeIf { it >= 0.0 }, str(o, "rk"), bool(o, "qi"),
            int(o, "dd") == 1, bool(o, "td"), num(o, "sx")?.takeIf { it in -1.5..1.5 }, bool(o, "cl"), num(o, "age"), str(o, "mk"),
            num(o, "br"), str(o, "bs"))
    }

    private fun pull(o: JSONObject?): Pull? {
        o ?: return null
        val lo = num(o, "lo") ?: return null; val hi = num(o, "hi") ?: return null
        val lv = int(o, "lv") ?: return null; val plv = int(o, "plv") ?: return null
        if (lo > hi || lv <= 0 || plv <= 0) return null
        return Pull(int(o, "s") ?: -1, int(o, "n")?.takeIf { it > 0 }, int(o, "ct"), lv, plv, lo, hi, num(o, "t") ?: 0.0)
    }

    /** a {"type":"aggrocov"} doc */
    fun coverage(o: JSONObject): Coverage? {
        val v = int(o, "v"); if (v != null && v != VERSION) return null
        val cv = HashMap<String, String>()
        o.optJSONObject("cv")?.let { c -> for (k in c.keys()) if (!c.isNull(k)) cv[k] = c.opt(k).toString() }
        fun pairs(a: JSONArray?): List<Pair<Int, Double>> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
            val p = a.optJSONArray(i) ?: return@mapNotNull null
            val id = p.optInt(0, 0); val yd = p.optDouble(1, Double.NaN)
            if (id == 0 || yd.isNaN()) null else id to yd }
        val ci = o.optJSONArray("ci")?.let { a -> (0 until a.length()).map { a.optDouble(it, Double.NaN) } } ?: emptyList()
        return Coverage(cv, pairs(o.optJSONArray("sp")), pairs(o.optJSONArray("it")), ci)
    }

    /** a {"type":"aggroack"} doc */
    fun ack(o: JSONObject): Ack? {
        val op = str(o, "op") ?: return null
        return Ack(op, int(o, "k"), int(o, "s"), int(o, "ok") == 1 || o.optBoolean("ok", false), str(o, "why"),
            if (o.has("mask") && !o.isNull("mask")) o.optLong("mask") else null)
    }

    // the omitted-is-unknown readers (JSON null, a wrong type: unknown too)
    private fun num(o: JSONObject, k: String): Double? = if (!o.has(k) || o.isNull(k)) null else o.optDouble(k, Double.NaN).takeIf { !it.isNaN() && !it.isInfinite() }
    private fun int(o: JSONObject, k: String): Int? = num(o, k)?.let { Math.round(it).toInt() }
    private fun str(o: JSONObject, k: String): String? = if (!o.has(k) || o.isNull(k)) null else o.opt(k)?.toString()?.takeIf { it.isNotEmpty() }
    private fun bool(o: JSONObject, k: String): Boolean? = if (!o.has(k) || o.isNull(k)) null else when (val x = o.opt(k)) {
        is Boolean -> x; is Number -> x.toInt() != 0; else -> x.toString() == "1" }
}
