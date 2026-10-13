package com.jrees.wowcompanion

import android.content.Context
import org.json.JSONObject

/**
 * Which pin an NPC wears on the map: a KEY naming UI Art's picture `ui/kit/mm_<key>.png`.
 *
 * WHY: the pin used to be picked by npcflag bit alone, and the bundled 3.3.5 data flags a
 * general-goods vendor who sells arrows VENDOR_AMMO and a bartender VENDOR_FOOD -- so General
 * Supplies wore an arrow and a bartender a drumstick (user, 2026-09-29). The NPC's SUBTITLE says
 * what it is, so it decides first; the bits decide only when it says nothing.
 *
 * ORDER (the map session's; the keyword table is UI Art's measured proposal, assets/ui/npc_kinds.json):
 *   1. the services the game tracks by role: mailbox, banker, auctioneer, flight master, innkeeper
 *   2. trainers: pet / riding / weapon master by subtitle; a class trainer's class word; a
 *      profession trainer's profession word; else the generic class book or profession pin
 *   3. stable master
 *   4. vendors: a "<profession> Supplies" subtitle is that profession; then the kind words
 *      (food before drink, so "Food & Drink" is food)
 *   5. no kind word: the bits in the old order -- REPAIR before VENDOR_REAGENT, because the other
 *      way round turns 565 of the 698 subtitle-less vendors into reagent pins (UI Art measured it)
 */
object NpcKind {
    private class Rules(
        val trainerOther: List<Pair<Regex, String>>,
        val cls: List<Pair<Regex, String>>,
        val prof: List<Pair<Regex, String>>,
        val vendor: List<Pair<Regex, String>>,
    )
    private var rules: Rules? = null
    private val SUPPLIES = Regex("\\bsuppl(y|ies)\\b", RegexOption.IGNORE_CASE)

    fun init(c: Context) {
        if (rules != null) return
        rules = try {
            val o = JSONObject(c.assets.open("ui/npc_kinds.json").bufferedReader().use { it.readText() })
            fun list(name: String): List<Pair<Regex, String>> {
                val a = o.optJSONArray(name) ?: return emptyList()
                return (0 until a.length()).mapNotNull { i ->
                    val r = a.optJSONArray(i) ?: return@mapNotNull null
                    Regex(r.optString(0), RegexOption.IGNORE_CASE) to r.optString(1)
                }
            }
            Rules(list("trainerOther"), list("class"), list("prof"), list("vendor"))
        } catch (t: Throwable) {
            Rules(emptyList(), emptyList(), emptyList(), emptyList())
        }
    }

    private fun first(rs: List<Pair<Regex, String>>, s: String): String? {
        for ((re, key) in rs) if (re.containsMatchIn(s)) return key
        return null
    }

    /** The pin for an NPC with role bits [f] and subtitle [sub] (null or "" when it has none). */
    fun key(f: Int, sub: String?): String {
        val r = rules
        val s = sub ?: ""
        when {
            f and PoiStore.SPIRIT != 0 -> return "spirit"
            f and PoiStore.BATTLEMASTER != 0 -> return "battlemaster"
            f and PoiStore.GUILD != 0 -> return "guild"
            f and PoiStore.MAILBOX != 0 -> return "mailbox"
            f and PoiStore.BANKER != 0 -> return "banker"
            f and PoiStore.AUCTIONEER != 0 -> return "auctioneer"
            f and PoiStore.FLIGHTMASTER != 0 -> return "flightmaster"
            f and PoiStore.INNKEEPER != 0 -> return "innkeeper"
        }
        if (f and (PoiStore.TRAINER_CLASS or PoiStore.TRAINER_PROF or PoiStore.TRAINER) != 0) {
            if (r != null && s.isNotEmpty()) {
                first(r.trainerOther, s)?.let { return it }
                if (f and PoiStore.TRAINER_PROF == 0) first(r.cls, s)?.let { return it }
                if (f and PoiStore.TRAINER_CLASS == 0) first(r.prof, s)?.let { return it }
            }
            return if (f and PoiStore.TRAINER_PROF != 0) "profession" else "class"
        }
        if (f and PoiStore.STABLEMASTER != 0) return "stablemaster"
        if (r != null && s.isNotEmpty()) {
            if (SUPPLIES.containsMatchIn(s)) first(r.prof, s)?.let { return it }
            first(r.vendor, s)?.let { return it }
        }
        return when {
            f and PoiStore.REPAIR != 0 -> "repair"
            f and PoiStore.TABARD != 0 -> "tabard"
            f and PoiStore.VENDOR_REAGENT != 0 -> "reagents"
            f and PoiStore.VENDOR_FOOD != 0 -> "food"
            f and PoiStore.VENDOR_AMMO != 0 -> "ammo"
            f and PoiStore.VENDOR_POISON != 0 -> "poisons"
            f and PoiStore.ANVIL != 0 -> "repair"          // the game's anvil (UI Art)
            f and PoiStore.FORGE != 0 -> "forge"
            f and PoiStore.VENDOR != 0 -> "vendor"
            else -> "generic"
        }
    }

    /** UI Art's round pictures fill their box edge to edge; drawn at 0.9x they read the same size. */
    fun isRound(key: String): Boolean =
        key.startsWith("class_") || key == "goods" || key == "tradegoods" || key == "armour" ||
            key == "drink" || key == "weapons" || key == "ranged" || key == "mount" ||
            key == "prof_enchanting" || key == "prof_skinning"
}
