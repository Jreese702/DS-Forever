package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * THE VENDOR'S FREE SLOTS (feat/vendor-bag-slots; the user, 2026-11-16: "when talking to a vendor in the buy screen I should see my bag
 * slots available as well as ammo slots available (if applicable)"): what the vendor window's strip shows beside its action button,
 * read from the Bags document the app already gets (Bags.lua collectBags; a bags-only "full" at every BAG_UPDATE_DELAYED, so a purchase,
 * a sale or a buyback shows at once, nothing polled, no add-on change):
 *  - BAGS: the free slots in the bags that take anything (`freeGeneral`: no quiver, ammo pouch, profession or reagent bag), the
 *    backpack's icon;
 *  - AMMO: only while a quiver or ammo pouch sits on the bag bar (its family has the game's arrows / bullets bit), the free slots in it
 *    (several: summed), its own icon.
 * A number the document does not give is left out, never guessed. Pure (no android.*): checked off-device.
 */
object VendorSlots {
    /** the game's bag families (GetContainerNumFreeSlots' bagType): 1 = arrows (a quiver), 2 = bullets (an ammo pouch) */
    const val FAM_AMMO = 0x3
    /** the backpack's icon (Home's clock band uses the same) */
    const val BAG_ICON = 133633
    /** an ammo bag's stand-in when neither its own icon nor the worn ammo's came (INV_Misc_Quiver) */
    const val AMMO_ICON = 134407

    /** [free] slots, drawn with [icon] (a file id) */
    data class Count(val free: Int, val icon: Int)

    /** what shows: [bags] (null = the document gave no count), [ammo] (null = no quiver / ammo pouch, or no count for it) */
    data class Slots(val bags: Count?, val ammo: Count?) {
        val none get() = bags == null && ammo == null
        /** the cells in their order, left to right: bags, then ammo */
        val cells: List<Count> get() = listOfNotNull(bags, ammo)
    }

    /** the vendor's slots from a bags document ([bags] = `full.bags`); null = nothing to show */
    fun of(bags: JSONObject?): Slots? {
        if (bags == null) return null
        val list = bags.optJSONArray("bags")
        val entries = if (list == null) emptyList() else (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        // the plain bags: the add-on's own sum (its rule: family 0, not the reagent bag); else the same sum over the bags it listed
        val plain = entries.filter { it.has("fam") && it.optInt("fam", -1) == 0 && !it.optBoolean("reagent") && it.has("free") }
        // review (feat/vendor-bag-slots): the add-on's freeGeneral STARTS at 0 and only a bag that gave both its family and its free
        // count adds to it, so a listed bag bar with no such bag says 0 for "not read" - no count, never a red 0
        val general = when {
            list != null && plain.isEmpty() -> null
            bags.has("freeGeneral") -> bags.optInt("freeGeneral", -1).takeIf { it >= 0 }
            plain.isNotEmpty() -> plain.sumOf { it.optInt("free", 0).coerceAtLeast(0) }
            else -> null
        }
        // the ammo bags on the bag bar: every one with the arrows / bullets bit and a free count
        val ammoBags = entries.filter { (it.optInt("fam", 0) and FAM_AMMO) != 0 && it.has("free") && it.optInt("free", -1) >= 0 }
        val ammo = if (ammoBags.isEmpty()) null else {
            val own = ammoBags.firstNotNullOfOrNull { b -> b.optInt("icon", 0).takeIf { it > 0 } }
            val worn = bags.optJSONObject("equipped")?.optJSONObject("ammo")?.optInt("icon", 0)?.takeIf { it > 0 }
            Count(ammoBags.sumOf { it.optInt("free", 0) }, own ?: worn ?: AMMO_ICON)
        }
        val s = Slots(general?.let { Count(it, BAG_ICON) }, ammo)
        return if (s.none) null else s
    }
}
