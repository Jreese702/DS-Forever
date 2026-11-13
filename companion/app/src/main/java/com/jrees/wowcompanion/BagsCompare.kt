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
 *
 * bags-nocmp1 (the user, 2026-11-13: "the bag items are still showing currently equipped, I dont want to see that, but I do want to see
 * the stat changes at the bottom of that weapon if I equip it"): the HOVER card shows no worn piece any more, only the changes at the
 * foot of the bag item's own tooltip ([hover]); the Compare sheet keeps the cards. Interact i159 sends a Bags compare WITHOUT the worn
 * piece's tooltip (`nw`: its lines are only the game's changes); the card takes that piece's own text from the equipped row ([wornCard]).
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
        if (row == null || !isGear(loc)) {
            // bags-nocmp1: as it came, a worn piece's own text over a compare sent without it
            val out = JSONArray()
            for (i in 0 until (given?.length() ?: 0)) given!!.optJSONObject(i)?.let { e ->
                out.put(withWorn(copyOf(e), e.optInt("slot", -1), worn[e.optInt("slot", -1)]))
            }
            return out
        }
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
                val card = withWorn(copyOf(e), slot, worn[slot]) ?: wornCard(slot, worn[slot]) ?: continue
                out.put(card.put("pair", MAIN))
                continue
            }
            // what the change is counted against: a two-hander against both hands, else this slot's piece
            val against = if (two) listOf(MAIN, OFF).filter { has(it) }.map { worn[it]?.optJSONArray("stats") }
                          else if (has(slot)) listOf(worn[slot]?.optJSONArray("stats")) else emptyList()
            val card = when {
                e != null -> withWorn(copyOf(e), slot, worn[slot])!!
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

    /**
     * bags-nocmp1: THE STAT CHANGES FOR THE HOVER CARD, to go at the foot of the bag item's own tooltip: a blank line, the header (the
     * game's "If you replace this item, ..." or the app's own), then each card's changes (the game's last `dn` lines, else the app's
     * `mine`). Where more than one piece would be replaced (two rings, two trinkets, a one-hander for a dual wielder) each piece's name
     * goes over its changes, in grey (an empty slot: its name and "Empty"). A two-hander's off hand (`pair`) has none of its own. Nothing to
     * show = empty.
     */
    fun hover(cards: JSONArray?): List<JSONObject> {
        if (cards == null) return emptyList()
        class Part(val name: JSONObject?, val head: JSONObject?, val lines: List<JSONObject>)
        val parts = ArrayList<Part>()
        for (i in 0 until cards.length()) {
            val c = cards.optJSONObject(i) ?: continue
            if (c.has("pair")) continue
            val l = c.optJSONArray("lines") ?: continue
            val n = c.optInt("dn", 0).takeIf { it > 0 } ?: c.optInt("mine", 0)
            if (n <= 0 || n > l.length()) continue
            val changes = (l.length() - n until l.length()).mapNotNull { k -> l.optJSONObject(k) }
            val head = if (l.length() - n - 1 >= 0) l.optJSONObject(l.length() - n - 1) else null
            val first = l.optJSONObject(0)
            val name = when {
                c.optBoolean("empty") -> first?.let { f ->
                    val e = l.optJSONObject(1)?.optString("t")?.takeIf { it.isNotBlank() }
                    line(if (e != null) "${f.optString("t")} (${e})" else f.optString("t"), DIM)
                }
                // grey, as a label: in its quality's colour an uncommon piece's name read as one more green gain
                first != null && first !== head -> line(first.optString("t"), DIM)
                else -> null
            }
            parts.add(Part(name, head, changes))
        }
        if (parts.isEmpty()) return emptyList()
        val out = ArrayList<JSONObject>()
        out.add(line(" "))
        out.add(parts.first().head?.let { JSONObject(it.toString()) } ?: line("Stat changes", HEAD))
        for ((k, p) in parts.withIndex()) {
            if (parts.size > 1) {
                if (k > 0) out.add(line(" "))
                p.name?.let { out.add(it) }
            }
            for (l in p.lines) out.add(JSONObject(l.toString()))
        }
        return out
    }

    /** bags-nocmp1: a game compare that came without the worn piece's tooltip (`nw`): that piece's own text over the changes */
    private fun withWorn(card: JSONObject?, slot: Int, w: JSONObject?): JSONObject? {
        if (card == null || !card.optBoolean("nw")) return card
        val head = wornCard(slot, w)?.optJSONArray("lines") ?: JSONArray().put(line("Slot $slot"))
        val l = card.optJSONArray("lines") ?: JSONArray()
        val merged = JSONArray()
        for (i in 0 until head.length()) merged.put(head.opt(i))
        for (i in 0 until l.length()) merged.put(l.opt(i))
        card.put("lines", merged)
        card.remove("nw")
        return card
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
