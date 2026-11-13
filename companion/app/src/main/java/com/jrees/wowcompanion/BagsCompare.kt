package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * bags-act1 (the user's picks, 2026-11-13, on E:/wow-build/bags_design_1113.md: "Swap deltas: keep them in the hover tooltip and the
 * Compare sheet; a two-hander compares vs main hand + off hand together; empty slot shows all gains via app-side diff of row.stats vs
 * worn stats when the game gives no delta").
 *
 * WHAT A BAG ITEM WOULD REPLACE, as cards (the same JSON as the add-on's tip.cmp: { slot, lines = [{t, c}, ...] }), for the hover
 * card's "Currently Equipped" and the Compare sheet's right side:
 *  - every compare the game gave (Interact's N.tipOf) stays as it is; its last `dn` lines are the game's own stat changes;
 *  - one without the game's changes gets the APP'S OWN COUNT under it: the bag item's stats (row.stats, the add-on's
 *    C_Item.GetItemStats) less the worn item's (bags.equipped.slots[n].stats), plain item data, never read off the screen;
 *  - an EMPTY slot the item can go in is a card of its own: the slot's name, "Empty", and all of the item's stats as gains;
 *  - a TWO-HANDER counts against BOTH hands: its changes under the main hand (the game's paired ones, `with`, or the app's count
 *    against the pair), the off hand's card marked `pair` with none of its own.
 * Pure (org.json only), tested off-device: BagsCompareTest.
 */
object BagsCompare {
    const val GAIN = "00ff00"           // the game's stat-change colours (Interact's delta lines)
    const val LOSS = "ff2020"
    const val HEAD = "ffd100"           // the game's gold header ("If you replace this item, ...")
    const val DIM = "808080"
    const val MAIN = 16; const val OFF = 17

    /** the worn slots each equip location goes in (Bags.lua ACT.SLOTS / Interact's EQUIP_SLOTS) */
    private val SLOTS = mapOf(
        "INVTYPE_HEAD" to listOf(1), "INVTYPE_NECK" to listOf(2), "INVTYPE_SHOULDER" to listOf(3), "INVTYPE_BODY" to listOf(4),
        "INVTYPE_CHEST" to listOf(5), "INVTYPE_ROBE" to listOf(5), "INVTYPE_WAIST" to listOf(6), "INVTYPE_LEGS" to listOf(7),
        "INVTYPE_FEET" to listOf(8), "INVTYPE_WRIST" to listOf(9), "INVTYPE_HAND" to listOf(10), "INVTYPE_FINGER" to listOf(11, 12),
        "INVTYPE_TRINKET" to listOf(13, 14), "INVTYPE_CLOAK" to listOf(15), "INVTYPE_WEAPON" to listOf(16, 17),
        "INVTYPE_2HWEAPON" to listOf(16, 17), "INVTYPE_WEAPONMAINHAND" to listOf(16), "INVTYPE_WEAPONOFFHAND" to listOf(17),
        "INVTYPE_SHIELD" to listOf(17), "INVTYPE_HOLDABLE" to listOf(17), "INVTYPE_RANGED" to listOf(18),
        "INVTYPE_RANGEDRIGHT" to listOf(18), "INVTYPE_THROWN" to listOf(18), "INVTYPE_RELIC" to listOf(18), "INVTYPE_TABARD" to listOf(19))

    fun isGear(loc: String?) = loc != null && SLOTS.containsKey(loc)
    fun twoHand(loc: String?) = loc == "INVTYPE_2HWEAPON"

    /** the slots an item of [loc] can go in: a one-hander's off hand only for a class that dual wields ([dualWield]) */
    fun slotsFor(loc: String?, dualWield: Boolean): List<Int> {
        val s = SLOTS[loc] ?: return emptyList()
        return if (loc == "INVTYPE_WEAPON" && !dualWield) listOf(MAIN) else s
    }

    data class Stat(val key: String, val name: String, val v: Double)

    /** the add-on's stats list ({k, n, v}) */
    fun stats(a: JSONArray?): List<Stat> {
        if (a == null) return emptyList()
        val out = ArrayList<Stat>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val k = o.optString("k").takeIf { it.isNotEmpty() } ?: continue
            if (!o.has("v")) continue
            val v = o.optDouble("v", Double.NaN).takeUnless { it.isNaN() } ?: continue
            out.add(Stat(k, o.optString("n").takeIf { it.isNotEmpty() && it[0].code > 3 } ?: k, v))
        }
        return out
    }

    private fun num(v: Double): String {
        val a = kotlin.math.abs(v)
        val s = if (a == kotlin.math.floor(a)) a.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", a)
        return (if (v < 0) "-" else "+") + s
    }

    /**
     * THE APP'S OWN COUNT: [bag] less the sum of [worn] (each the add-on's stats list; none = an empty slot), as the game writes its
     * changes ("+3 Stamina" green, "-2 Strength" red). In the bag item's own order, then what only the worn ones have. Nothing equal.
     */
    fun diff(bag: JSONArray?, worn: List<JSONArray?>): List<JSONObject> {
        val b = stats(bag)
        val w = LinkedHashMap<String, Stat>()
        for (a in worn) for (s in stats(a)) w[s.key] = w[s.key]?.let { it.copy(v = it.v + s.v) } ?: s
        val out = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        for (s in b) {
            seen += s.key
            val d = s.v - (w[s.key]?.v ?: 0.0)
            if (kotlin.math.abs(d) > 1e-9) out.add(line("${num(d)} ${s.name}", if (d > 0) GAIN else LOSS))
        }
        for ((k, s) in w) if (k !in seen && kotlin.math.abs(s.v) > 1e-9) out.add(line("${num(-s.v)} ${s.name}", if (-s.v > 0) GAIN else LOSS))
        return out
    }

    private fun line(t: String, c: String? = null): JSONObject = JSONObject().put("t", t).also { if (c != null) it.put("c", c) }

    /** the words the cards use, in the player's language (the view passes Tr.t's) */
    data class Words(val empty: String = "Empty", val changes: String = "Stat changes")

    /**
     * The cards for bag item [row] (its `equip`, `stats`) under the game's tooltip [tip] (its `cmp`), with what is worn ([worn]: slot ->
     * the add-on's equipped row: id, name, stats, tip). An item that is not gear: the game's compare as it came (none = empty).
     */
    fun cards(tip: JSONObject?, row: JSONObject?, worn: Map<Int, JSONObject>, dualWield: Boolean, words: Words = Words()): JSONArray {
        val given = tip?.optJSONArray("cmp")
        val loc = row?.optString("equip")?.takeIf { it.isNotEmpty() }
        if (row == null || !isGear(loc)) return given ?: JSONArray()
        val byGame = LinkedHashMap<Int, JSONObject>()
        for (i in 0 until (given?.length() ?: 0)) given!!.optJSONObject(i)?.let { e -> byGame[e.optInt("slot", -1)] = e }
        val base = slotsFor(loc, dualWield)
        // the game compared somewhere this item's own slots do not say (an off-hand piece against a worn two-hander): the game's slots
        val targets = if (byGame.keys.any { it !in base }) byGame.keys.toList() else base
        fun has(slot: Int) = worn[slot]?.optInt("id", 0)?.let { it > 0 } == true
        val two = twoHand(loc)
        val out = JSONArray()
        for (slot in targets) {
            val e = byGame[slot]
            val filled = has(slot) || e != null
            if (two && slot == OFF) {
                // the off hand under a two-hander: shown when worn, its loss counted under the main hand
                if (!filled) continue
                val card = copyOf(e) ?: wornCard(slot, worn[slot]) ?: continue
                out.put(card.put("pair", MAIN))
                continue
            }
            // what the change is counted against: a two-hander against both hands, else this slot's piece
            val against = if (two) listOf(MAIN, OFF).filter { has(it) }.map { worn[it]?.optJSONArray("stats") }
                          else if (has(slot)) listOf(worn[slot]?.optJSONArray("stats")) else emptyList()
            val card = when {
                e != null -> copyOf(e)!!
                has(slot) -> wornCard(slot, worn[slot]) ?: continue
                else -> emptyCard(slot, worn[slot], words)
            }
            if (two && e?.has("with") == true) card.put("with", e.optInt("with"))
            val gameGave = card.optInt("dn", 0) > 0
            if (!gameGave) {
                val d = diff(row.optJSONArray("stats"), against)
                if (d.isNotEmpty()) {
                    val lines = card.optJSONArray("lines") ?: JSONArray().also { card.put("lines", it) }
                    lines.put(line(" ")); lines.put(line(words.changes, HEAD))
                    for (l in d) lines.put(l)
                    card.put("mine", d.size)                  // the app's own count, not the game's
                }
            }
            out.put(card)
        }
        return out
    }

    private fun copyOf(e: JSONObject?): JSONObject? {
        if (e == null) return null
        val c = JSONObject(e.toString())
        if (!c.has("lines")) c.put("lines", JSONArray())
        return c
    }

    /** a worn piece the game gave no compare for: its own kept tooltip (the add-on's equipped row tip), else its name */
    private fun wornCard(slot: Int, w: JSONObject?): JSONObject? {
        if (w == null) return null
        val lines = JSONArray()
        val t = w.optJSONArray("tip")
        if (t != null && t.length() > 0) for (i in 0 until t.length()) t.optJSONObject(i)?.let { lines.put(JSONObject(it.toString())) }
        else lines.put(line(w.optString("item").takeIf { it.isNotEmpty() } ?: w.optString("name"), null))
        return JSONObject().put("slot", slot).put("lines", lines)
    }

    /** an empty slot: its name, "Empty"; the changes (all gains) go under it */
    private fun emptyCard(slot: Int, w: JSONObject?, words: Words): JSONObject {
        val name = w?.optString("name")?.takeIf { it.isNotEmpty() } ?: "Slot $slot"
        val lines = JSONArray().put(line(name, DIM)).put(line(words.empty, DIM))
        return JSONObject().put("slot", slot).put("empty", true).put("lines", lines)
    }
}
