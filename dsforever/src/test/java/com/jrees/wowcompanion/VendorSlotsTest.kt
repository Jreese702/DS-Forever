package com.jrees.wowcompanion

import com.jrees.wowcompanion.VendorSlots.Count
import com.jrees.wowcompanion.VendorSlots.Tone
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * THE VENDOR'S BAG AND AMMO FIGURES (feat/vendor-bag-slots; the user, 2026-11-16: "It should show like bag slots out of how many available
 * and for ammo it should show ammo/total available space. The numbers on their own don't mean anything"), off-device: what [VendorSlots]
 * reads from the Bags document (Bags.lua collectBags: per bag `slots` / `free` / `fam` / `icon`, the stacks' `count`, the worn ammo's
 * `stack`, bags-vslots1). The vendor-fixtures are the REAL add-on's documents (tools/test_vendor_slots.py writes them).
 */
class VendorSlotsTest {
    private fun bag(bag: Int, slots: Int, free: Int?, fam: Int?, icon: Int? = null, reagent: Boolean = false) = JSONObject().apply {
        put("bag", bag); put("slots", slots)
        if (free != null) put("free", free)
        if (fam != null) put("fam", fam)
        if (icon != null) put("icon", icon)
        if (reagent) put("reagent", true)
    }

    /** a stack in [bag] */
    private fun item(bag: Int, slot: Int, count: Int?) = JSONObject().put("bag", bag).put("slot", slot).put("id", 2512).apply {
        if (count != null) put("count", count)
    }

    private fun doc(vararg bags: JSONObject, items: List<JSONObject> = emptyList(), ammoStack: Int? = null, ammoIcon: Int? = null) =
        JSONObject().apply {
            put("bags", JSONArray().apply { bags.forEach { put(it) } })
            put("items", JSONArray().apply { items.forEach { put(it) } })
            if (ammoStack != null || ammoIcon != null) put("equipped", JSONObject().put("ammo", JSONObject().put("id", 2512).apply {
                if (ammoStack != null) put("stack", ammoStack)
                if (ammoIcon != null) put("icon", ammoIcon)
            }))
        }

    private fun fixture(name: String): JSONObject =
        JSONObject(javaClass.classLoader!!.getResourceAsStream("vendor-fixtures/$name.json")!!.bufferedReader().readText())

    // ---- the add-on's own documents (tools/test_vendor_slots.py)

    @Test fun hunterFromTheAddOn() {
        val s = VendorSlots.of(fixture("bags_hunter"))!!
        assertEquals(Count(22, 30, VendorSlots.BAG_ICON, Tone.PLAIN), s.bags)       // 16 + 14 plain slots, 10 + 12 used
        assertEquals(Count(550, 1600, 134407, Tone.PLAIN), s.ammo)                  // 200 + 200 + 150 in an 8-slot quiver of 200s
        assertEquals("22 / 30", VendorSlots.text(s.bags!!))
        assertEquals("550 / 1600", VendorSlots.text(s.ammo!!))
    }

    @Test fun thousandStacksFromTheAddOn() {
        val s = VendorSlots.of(fixture("bags_hunter_1000"))!!
        assertEquals(Count(2340, 12000, 134407, Tone.PLAIN), s.ammo)                // 12 slots x 1000 (GetItemMaxStackSizeByID)
        assertEquals("2340 / 12k", VendorSlots.text(s.ammo!!))
    }

    @Test fun fullBagsAndLowAmmoFromTheAddOn() {
        val s = VendorSlots.of(fixture("bags_full_pouch_low"))!!
        assertEquals(Count(16, 16, VendorSlots.BAG_ICON, Tone.OUT), s.bags)          // full: red
        assertEquals(Count(60, 2000, 133581, Tone.LOW), s.ammo)                      // 60 of a 10-slot pouch of 200s: under a tenth, amber
    }

    @Test fun anUnreadStackSizeIsTheClassic200FromTheAddOn() {
        assertEquals(Count(200, 1600, 134407, Tone.PLAIN), VendorSlots.of(fixture("bags_hunter_stack_unread"))!!.ammo)
    }

    @Test fun aWarlocksSoulBagIsNeitherFromTheAddOn() {
        val s = VendorSlots.of(fixture("bags_warlock_full"))!!
        assertEquals(Count(16, 16, VendorSlots.BAG_ICON, Tone.OUT), s.bags)          // the 20-slot soul bag is not counted
        assertNull(s.ammo)
    }

    @Test fun anEmptyQuiverFromTheAddOn() {
        val s = VendorSlots.of(fixture("bags_hunter_empty_quiver"))!!
        assertEquals(Count(1, 16, VendorSlots.BAG_ICON, Tone.PLAIN), s.bags)
        assertEquals(Count(0, 1600, 134407, Tone.OUT), s.ammo)                      // no ammo: red
    }

    // ---- the rules, made-up documents

    @Test fun noAmmoBagShowsTheBagsAlone() {
        val s = VendorSlots.of(doc(bag(0, 16, 9, 0), bag(1, 6, 3, 0, 133633)))!!
        assertEquals(Count(10, 22, VendorSlots.BAG_ICON, Tone.PLAIN), s.bags)
        assertNull(s.ammo)
        assertEquals(1, s.cells.size)
    }

    @Test fun bagsAreUsedOverTotalAndRedOnlyWhenFull() {
        assertEquals(Count(21, 22, VendorSlots.BAG_ICON, Tone.PLAIN), VendorSlots.of(doc(bag(0, 16, 1, 0), bag(1, 6, 0, 0)))!!.bags)
        assertEquals(Count(22, 22, VendorSlots.BAG_ICON, Tone.OUT), VendorSlots.of(doc(bag(0, 16, 0, 0), bag(1, 6, 0, 0)))!!.bags)
    }

    @Test fun ammoIsWhatTheAmmoBagsHoldOverTheirSlotsTimesTheStack() {
        val s = VendorSlots.of(doc(bag(0, 16, 9, 0), bag(1, 14, 11, 1, 134408),
            items = listOf(item(1, 1, 200), item(1, 2, 200), item(1, 3, 37), item(0, 4, 200)), ammoStack = 200))!!
        // the 200 arrows in the backpack are not in the quiver's space: 437 of 14 x 200
        assertEquals(Count(437, 2800, 134408, Tone.PLAIN), s.ammo)
        assertEquals(listOf(s.bags, s.ammo), s.cells)        // bags first, then ammo
    }

    @Test fun ammoTones() {
        fun tone(n: Int) = VendorSlots.of(doc(bag(0, 16, 9, 0), bag(1, 10, 9, 1), items = listOf(item(1, 1, n)), ammoStack = 200))!!.ammo!!.tone
        assertEquals(Tone.OUT, tone(0))                      // no ammo: red
        assertEquals(Tone.LOW, tone(199))                    // under a tenth of 2000: amber
        assertEquals(Tone.PLAIN, tone(200))                  // a tenth: plain
        val full = VendorSlots.of(doc(bag(0, 16, 9, 0), bag(1, 2, 0, 1), items = listOf(item(1, 1, 200), item(1, 2, 200)), ammoStack = 200))!!
        assertEquals(Count(400, 400, VendorSlots.AMMO_ICON, Tone.PLAIN), full.ammo)  // a full quiver is the goal: plain
    }

    @Test fun anAmmoPouchCountsToo() {
        val s = VendorSlots.of(doc(bag(0, 16, 0, 0), bag(2, 8, 6, 2, 133581), items = listOf(item(2, 1, 200), item(2, 2, 200)), ammoStack = 200))!!
        assertEquals(Count(16, 16, VendorSlots.BAG_ICON, Tone.OUT), s.bags)
        assertEquals(Count(400, 1600, 133581, Tone.PLAIN), s.ammo)
    }

    @Test fun twoAmmoBagsAreSummedUnderTheFirstIcon() {
        val s = VendorSlots.of(doc(bag(0, 16, 9, 0), bag(1, 14, 13, 1, 134408), bag(2, 8, 7, 2, 133581),
            items = listOf(item(1, 1, 200), item(1, 2, 200), item(1, 3, 200), item(2, 1, 100)), ammoStack = 200))!!
        assertEquals(Count(700, 4400, 134408, Tone.PLAIN), s.ammo)                  // (14 + 8) x 200
    }

    @Test fun anUnreadStackSizeIsTheClassic200OrTheBiggestStackSeen() {
        assertEquals(1600, VendorSlots.of(doc(bag(1, 8, 7, 1), items = listOf(item(1, 1, 150))))!!.ammo!!.of)
        // a 1000-stack client whose ammo was not read: a stack of 1000 is proof the stack is at least that
        assertEquals(8000, VendorSlots.of(doc(bag(1, 8, 7, 1), items = listOf(item(1, 1, 1000))))!!.ammo!!.of)
    }

    @Test fun otherSpecialBagsAreNeitherBagsNorAmmo() {
        // a soul bag (4), an herb bag (32), the reagent bag: not where loot or bought goods go, and no ammo
        val s = VendorSlots.of(doc(bag(0, 16, 4, 0), bag(1, 20, 20, 4), bag(2, 24, 24, 32), bag(5, 16, 16, 0, reagent = true)))!!
        assertEquals(Count(12, 16, VendorSlots.BAG_ICON, Tone.PLAIN), s.bags)
        assertNull(s.ammo)
    }

    @Test fun anAmmoBagWithoutItsIconTakesTheWornAmmosThenTheStandIn() {
        assertEquals(VendorSlots.AMMO_ICON, VendorSlots.of(doc(bag(0, 16, 9, 0), bag(1, 14, 14, 1), ammoStack = 200))!!.ammo!!.icon)
        assertEquals(132382, VendorSlots.of(doc(bag(0, 16, 9, 0), bag(1, 14, 14, 1), ammoStack = 200, ammoIcon = 132382))!!.ammo!!.icon)
    }

    @Test fun aStackWithoutItsCountKeepsTheLastFigure() {
        // a freshly bought stack can have no info table at BAG_UPDATE_DELAYED: its count is not in the doc; the figure shown stays
        val before = VendorSlots.of(doc(bag(0, 16, 9, 0), bag(1, 8, 7, 1, 134408), items = listOf(item(1, 1, 200)), ammoStack = 200))!!
        val mid = doc(bag(0, 16, 8, 0), bag(1, 8, 6, 1, 134408), items = listOf(item(1, 1, 200), item(1, 2, null)), ammoStack = 200)
        val s = VendorSlots.of(mid, before)!!
        assertEquals(Count(8, 16, VendorSlots.BAG_ICON, Tone.PLAIN), s.bags)        // the bags move on
        assertEquals(before.ammo, s.ammo)
        assertNull(VendorSlots.of(mid)!!.ammo)                                       // nothing shown before: nothing guessed
        // no stack list at all: the same
        assertEquals(before.ammo, VendorSlots.of(JSONObject().put("bags", JSONArray().put(bag(1, 8, 6, 1, 134408))), before)!!.ammo)
    }

    @Test fun figuresAreCompact() {
        assertEquals("0", VendorSlots.num(0))
        assertEquals("9999", VendorSlots.num(9999))
        assertEquals("10k", VendorSlots.num(10_000))
        assertEquals("12.3k", VendorSlots.num(12_399))        // cut, never rounded up: a figure short of its total never reads as it
        assertEquals("99.9k", VendorSlots.num(99_999))
        assertEquals("150k", VendorSlots.num(150_999))
        assertEquals("1850 / 3200", VendorSlots.text(Count(1850, 3200, 0, Tone.PLAIN)))
    }

    @Test fun nothingKnownShowsNothing() {
        assertNull(VendorSlots.of(null))
        assertNull(VendorSlots.of(JSONObject()))
        assertNull(VendorSlots.of(JSONObject().put("free", 12).put("total", 30)))   // the totals alone count special bags too: no guess
        assertNull(VendorSlots.of(JSONObject().put("freeGeneral", 7)))               // free alone: no total, so no figure
        assertNull(VendorSlots.of(doc(bag(0, 16, null, 0))))                          // no free count anywhere
        assertNull(VendorSlots.of(doc(bag(0, 16, 5, null))))                          // a bag without its family: neither plain nor ammo
    }

    @Test fun aPlainBagNotReadIsLeftOutOfBothFigures() {
        // the add-on's rule (freeGeneral): only a bag that gave its family and its free count is a plain bag
        val s = VendorSlots.of(doc(bag(0, 16, 9, 0), bag(1, 14, null, 0), bag(2, 8, 8, 1, 134408), ammoStack = 200))!!
        assertEquals(Count(7, 16, VendorSlots.BAG_ICON, Tone.PLAIN), s.bags)
        assertNull(VendorSlots.of(doc(bag(0, 16, null, 0), bag(1, 14, 14, 1, 134408), ammoStack = 200))!!.bags)
    }
}
