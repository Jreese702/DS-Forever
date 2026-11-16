package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * THE VENDOR'S FREE SLOTS (feat/vendor-bag-slots; the user, 2026-11-16: "when talking to a vendor in the buy screen I should see my bag
 * slots available as well as ammo slots available (if applicable)"), off-device: what [VendorSlots] reads from the Bags document
 * (Bags.lua collectBags: `freeGeneral`, and per bag `fam` / `free` / `icon`).
 */
class VendorSlotsTest {
    private fun bag(bag: Int, slots: Int, free: Int?, fam: Int?, icon: Int? = null, reagent: Boolean = false) = JSONObject().apply {
        put("bag", bag); put("slots", slots)
        if (free != null) put("free", free)
        if (fam != null) put("fam", fam)
        if (icon != null) put("icon", icon)
        if (reagent) put("reagent", true)
    }

    private fun doc(vararg bags: JSONObject, freeGeneral: Int? = null) = JSONObject().apply {
        put("bags", JSONArray().apply { bags.forEach { put(it) } })
        if (freeGeneral != null) put("freeGeneral", freeGeneral)
    }

    @Test fun noAmmoBagShowsTheBagsAlone() {
        val s = VendorSlots.of(doc(bag(0, 16, 9, 0), bag(1, 6, 3, 0, 133633), freeGeneral = 12))!!
        assertEquals(VendorSlots.Count(12, VendorSlots.BAG_ICON), s.bags)
        assertNull(s.ammo)
        assertEquals(1, s.cells.size)
    }

    @Test fun aQuiverAddsItsFreeSlotsWithItsOwnIcon() {
        val s = VendorSlots.of(doc(bag(0, 16, 9, 0), bag(1, 14, 5, 1, 134408), freeGeneral = 9))!!
        assertEquals(VendorSlots.Count(9, VendorSlots.BAG_ICON), s.bags)
        assertEquals(VendorSlots.Count(5, 134408), s.ammo)
        assertEquals(listOf(s.bags, s.ammo), s.cells)        // bags first, then ammo
    }

    @Test fun anAmmoPouchCountsToo() {
        val s = VendorSlots.of(doc(bag(0, 16, 0, 0), bag(2, 8, 3, 2, 133581), freeGeneral = 0))!!
        assertEquals(0, s.bags!!.free)                       // a full bag reads 0 (the view draws it red), never left out
        assertEquals(VendorSlots.Count(3, 133581), s.ammo)
    }

    @Test fun otherSpecialBagsAreNeitherBagsNorAmmo() {
        // a soul bag (4), an herb bag (32), the reagent bag: not where loot or bought goods go, and no ammo
        val s = VendorSlots.of(doc(bag(0, 16, 4, 0), bag(1, 20, 20, 4), bag(2, 24, 24, 32), bag(5, 16, 16, 0, reagent = true)))!!
        assertEquals(4, s.bags!!.free)                       // without freeGeneral: the plain bags' own sum, the add-on's rule
        assertNull(s.ammo)
    }

    @Test fun freeGeneralIsTheAddOnsWord() {
        // the add-on's own sum wins over the list (it is the same rule; the list may be cut)
        assertEquals(7, VendorSlots.of(doc(bag(0, 16, 9, 0), freeGeneral = 7))!!.bags!!.free)
    }

    @Test fun twoAmmoBagsAreSummedUnderTheFirstIcon() {
        val s = VendorSlots.of(doc(bag(0, 16, 9, 0), bag(1, 14, 2, 1, 134408), bag(2, 8, 6, 2, 133581), freeGeneral = 9))!!
        assertEquals(VendorSlots.Count(8, 134408), s.ammo)
    }

    @Test fun anAmmoBagWithoutItsIconTakesTheWornAmmosThenTheStandIn() {
        val d = doc(bag(0, 16, 9, 0), bag(1, 14, 5, 1), freeGeneral = 9)
        assertEquals(VendorSlots.AMMO_ICON, VendorSlots.of(d)!!.ammo!!.icon)
        d.put("equipped", JSONObject().put("ammo", JSONObject().put("id", 2512).put("icon", 132382).put("count", 400)))
        assertEquals(132382, VendorSlots.of(d)!!.ammo!!.icon)
    }

    @Test fun anAmmoBagWithoutAFreeCountIsLeftOut() {
        val s = VendorSlots.of(doc(bag(0, 16, 9, 0), bag(1, 14, null, 1, 134408), freeGeneral = 9))!!
        assertNull(s.ammo)
    }

    @Test fun nothingKnownShowsNothing() {
        assertNull(VendorSlots.of(null))
        assertNull(VendorSlots.of(JSONObject()))
        assertNull(VendorSlots.of(JSONObject().put("free", 12)))           // the total alone counts special bags too: not guessed from
        assertNull(VendorSlots.of(doc(bag(0, 16, null, 0))))                // no free count anywhere
        // a bag without its family: neither plain nor ammo
        assertNull(VendorSlots.of(doc(bag(0, 16, 5, null))))
    }

    @Test fun aNegativeFreeGeneralIsNoCount() {
        assertNull(VendorSlots.of(doc(freeGeneral = -1)))
    }
}
