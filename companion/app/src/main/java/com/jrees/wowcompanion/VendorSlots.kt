package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * THE VENDOR'S BAG AND AMMO SPACE (feat/vendor-bag-slots; the user, 2026-11-16: "when talking to a vendor in the buy screen I should see my
 * bag slots available as well as ammo slots available (if applicable)", then: "It should show like bag slots out of how many available
 * and for ammo it should show ammo/total available space. The numbers on their own don't mean anything"): what the vendor window's
 * strip shows beside its action button, read from the Bags document the app already gets (Bags.lua collectBags; a bags-only "full" at
 * every BAG_UPDATE_DELAYED, so a purchase, a sale or a buyback shows at once, nothing polled):
 *  - BAGS: USED / TOTAL slots of the bags that take anything (family 0, the backpack too; not a quiver, ammo pouch, soul, profession or
 *    the reagent bag), as the game's bag bar counts them; the backpack's icon. Red when full.
 *  - AMMO: only while a quiver or ammo pouch sits on the bag bar (its family has the game's arrows / bullets bit): the ammo IN those bags
 *    (their stacks' counts) / what they hold (their slots x the worn ammo's stack size: `equipped.ammo.stack`, Bags.lua bags-vslots1;
 *    unread = the game's Classic 200, or the biggest stack seen in them if that is more); its own icon. Red at 0, amber under a tenth.
 *    A quiver that is full is the goal, not a warning: plain.
 * A number the document does not give is left out, never guessed; an ammo stack whose count has not come yet keeps the last figure.
 * Pure (no android.*): checked off-device.
 */
object VendorSlots {
    /** the game's bag families (GetContainerNumFreeSlots' bagType): 1 = arrows (a quiver), 2 = bullets (an ammo pouch) */
    const val FAM_AMMO = 0x3
    /** the backpack's icon (Home's clock band uses the same) */
    const val BAG_ICON = 133633
    /** an ammo bag's stand-in when neither its own icon nor the worn ammo's came (INV_Misc_Quiver) */
    const val AMMO_ICON = 134407
    /** arrows' / shots' stack size in Classic, when the worn ammo's was not read */
    const val AMMO_STACK = 200
    /** below this share of what the ammo bags hold the ammo figure turns amber (a tenth) */
    const val AMMO_LOW = 0.10

    /** how a figure is drawn: plain, LOW (amber), OUT (red: bags full / no ammo) */
    enum class Tone { PLAIN, LOW, OUT }

    /** [have] of [of], drawn with [icon] (a file id) in [tone] */
    data class Count(val have: Int, val of: Int, val icon: Int, val tone: Tone)

    /** what shows: [bags] (null = the document gave no count), [ammo] (null = no quiver / ammo pouch, or no figure for it) */
    data class Slots(val bags: Count?, val ammo: Count?) {
        val none get() = bags == null && ammo == null
        /** the cells in their order, top / left first: bags, then ammo */
        val cells: List<Count> get() = listOfNotNull(bags, ammo)
    }

    /** a figure as the strip writes it: whole up to 9999, then thousands with one decimal (cut, never rounded up: 12.3k, 24k, 150k) */
    fun num(n: Int): String = when {
        n < 10_000 -> n.toString()
        n < 100_000 -> (n / 100).let { t -> if (t % 10 == 0) "${t / 10}k" else "${t / 10}.${t % 10}k" }
        else -> "${n / 1000}k"
    }

    /** the cell's words: "52 / 64", "1850 / 3200" */
    fun text(c: Count) = "${num(c.have)} / ${num(c.of)}"

    /**
     * The vendor's figures from a bags document ([bags] = `full.bags`); null = nothing to show. [last] = what was shown before: an ammo
     * stack whose count has not come yet (a freshly bought stack can have no info table at BAG_UPDATE_DELAYED) keeps its figure.
     */
    fun of(bags: JSONObject?, last: Slots? = null): Slots? {
        if (bags == null) return null
        val list = bags.optJSONArray("bags")
        val entries = if (list == null) emptyList() else (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        // the plain bags, the add-on's freeGeneral rule (family 0, not the reagent bag, its free count read): used / total over them
        val plain = entries.filter {
            it.has("fam") && it.optInt("fam", -1) == 0 && !it.optBoolean("reagent") && it.optInt("free", -1) >= 0 && it.optInt("slots", 0) > 0
        }
        val general = if (plain.isEmpty()) null else {
            val total = plain.sumOf { it.optInt("slots", 0) }
            val used = plain.sumOf { (it.optInt("slots", 0) - it.optInt("free", 0)).coerceAtLeast(0) }
            Count(used, total, BAG_ICON, if (used >= total) Tone.OUT else Tone.PLAIN)
        }
        return Slots(general, ammoOf(bags, entries, last)).takeIf { !it.none }
    }

    /** the ammo in the quivers / ammo pouches on the bag bar, over what they hold */
    private fun ammoOf(bags: JSONObject, entries: List<JSONObject>, last: Slots?): Count? {
        val ammoBags = entries.filter { (it.optInt("fam", 0) and FAM_AMMO) != 0 && it.optInt("slots", 0) > 0 && it.has("bag") }
        if (ammoBags.isEmpty()) return null
        val worn = bags.optJSONObject("equipped")?.optJSONObject("ammo")
        val icon = ammoBags.firstNotNullOfOrNull { b -> b.optInt("icon", 0).takeIf { it > 0 } }
            ?: worn?.optInt("icon", 0)?.takeIf { it > 0 } ?: AMMO_ICON
        // the stacks in them: every one must have its count, else the figure is not known now
        val ids = ammoBags.map { it.optInt("bag") }.toSet()
        val items = bags.optJSONArray("items")
        val stacks = if (items == null) null else (0 until items.length()).mapNotNull { items.optJSONObject(it) }
            .filter { it.has("bag") && it.optInt("bag") in ids }
        val known = stacks != null && stacks.all { it.optInt("count", -1) >= 0 }
        if (!known) return last?.ammo?.copy(icon = icon)
        val have = stacks!!.sumOf { it.optInt("count", 0) }
        val stack = worn?.optInt("stack", 0)?.takeIf { it > 0 } ?: maxOf(AMMO_STACK, stacks.maxOfOrNull { it.optInt("count", 0) } ?: 0)
        val of = ammoBags.sumOf { it.optInt("slots", 0) } * stack
        val tone = when {
            have <= 0 -> Tone.OUT
            have < of * AMMO_LOW -> Tone.LOW
            else -> Tone.PLAIN
        }
        return Count(have, of, icon, tone)
    }
}
