package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * char-more (the user, 2026-11-13: "we should have options there like unequip, replace and enchant"): the Replace list's slot rules and
 * route ([WornReplace]) and the Enchant list's filter ([KitFit]).
 */
class WornReplaceTest {
    private fun row(equip: String, ilvl: Int = 10, nouse: Boolean = false) =
        JSONObject().put("equip", equip).put("ilvl", ilvl).put("name", equip).apply { if (nouse) put("nouse", true) }

    private val bags = listOf(
        Triple(0, 1, row("INVTYPE_FINGER", 12)),
        Triple(0, 2, row("INVTYPE_WEAPON", 15)),
        Triple(0, 3, row("INVTYPE_2HWEAPON", 20)),
        Triple(0, 4, row("INVTYPE_SHIELD")),
        Triple(1, 1, row("INVTYPE_CHEST", 8)),
        Triple(1, 2, row("INVTYPE_ROBE", 9)),
        Triple(1, 3, row("INVTYPE_CHEST", 30, nouse = true)),       // plate for a shaman: the game won't
        Triple(1, 4, row("INVTYPE_TRINKET")),
        Triple(2, 1, row("INVTYPE_WEAPONMAINHAND")),
        Triple(2, 2, row("INVTYPE_HOLDABLE")),
        Triple(2, 3, row("INVTYPE_BAG")),
        Triple(2, 4, JSONObject().put("name", "Linen Cloth")),
    )

    private fun at(slot: Int, dw: Boolean = false) = WornReplace.picks(bags, slot, dw).map { it.bag to it.slot }

    @Test fun theGamesSlotRules() {
        assertEquals("chest and robe, best first; the unusable plate left out", listOf(1 to 2, 1 to 1), at(5))
        assertEquals("a ring either finger", listOf(0 to 1), at(11)); assertEquals(listOf(0 to 1), at(12))
        assertEquals("a trinket either slot", listOf(1 to 4), at(13)); assertEquals(listOf(1 to 4), at(14))
        assertEquals("main hand: 2H, 1H, main-hand", listOf(0 to 3, 0 to 2, 2 to 1), at(16))
        assertEquals("off hand, no dual wield: shield and held only", listOf(0 to 4, 2 to 2), at(17))
        assertEquals("off hand, dual wield: a one-hander too; never a two-hander", listOf(0 to 2, 0 to 4, 2 to 2), at(17, dw = true))
        assertTrue("a bag and cloth go nowhere", at(1).isEmpty() && at(0).isEmpty() && at(20).isEmpty())
    }

    @Test fun exactWhenTheGamesOwnEquipLandsThere() {
        fun exact(slot: Int, bag: Int, s: Int, dw: Boolean = false) = WornReplace.picks(bags, slot, dw).first { it.bag == bag && it.slot == s }.exact
        assertTrue(exact(5, 1, 1)); assertTrue(exact(16, 0, 3)); assertTrue(exact(17, 0, 4)); assertTrue(exact(16, 0, 2))
        assertFalse("a ring: two slots", exact(12, 0, 1))
        assertFalse("a dual wielder's one-hander: two slots", exact(16, 0, 2, dw = true))
    }

    private val pad = JSONObject().put("rows", JSONObject().put("bag0", 1).put("bag1", 2).put("bagsort", 6).put("bmvq", 7).put("bmv", 55))
        .put("sizes", JSONObject().put("bagsort", 40).put("bmvq", 40).put("bmv", 40))

    @Test fun theRoute() {
        val chest = WornReplace.picks(bags, 5, false).first { it.slot == 1 }
        assertEquals("one secure tap on the bag slot's own cell", listOf("bag1" to 0), WornReplace.cells(pad, chest, 5, combat = false))
        val ring = WornReplace.picks(bags, 12, false).first()
        assertEquals("a ring into THAT finger: the drag (named pick, then the worn slot)",
            listOf("bagsort" to 10, "bmv" to 0, "bmvq" to 11), WornReplace.cells(pad, ring, 12, combat = false))
        assertNull("the drag is refused in a fight", WornReplace.cells(pad, ring, 12, combat = true))
        assertEquals("In combat", WornReplace.why(ring, 12, combat = true))
        val shield = WornReplace.picks(bags, 17, false).first { it.slot == 4 }
        assertEquals("a shield in a fight: the game's own equip", listOf("bag0" to 3), WornReplace.cells(pad, shield, 17, combat = true))
        assertNull(WornReplace.why(shield, 17, combat = true))
        assertNull("no pad row for the bag: nothing tapped", WornReplace.cells(JSONObject().put("rows", JSONObject()), chest, 5, combat = false))
    }

    @Test fun armourInAFightIsNeverOffered() {
        val chest = WornReplace.picks(bags, 5, false).first()
        assertFalse(WornReplace.now(chest, 5, combat = true))
        assertNull(WornReplace.cells(pad, chest, 5, combat = true))
    }

    @Test fun kitFits() {
        val k1 = JSONObject().put("kit", true).put("name", "Light Armor Kit").put("kfy", ",5,10,").put("kfn", ",6,")
        val k2 = JSONObject().put("kit", true).put("name", "Sharpening Stone").put("kfn", ",5,10,")
        val k3 = JSONObject().put("kit", true).put("name", "Unknown oil")
        val kits = listOf(Triple(0, 1, k3), Triple(0, 2, k2), Triple(0, 3, k1))
        assertEquals(KitFit.Fit.YES, KitFit.fit(k1, 5)); assertEquals(KitFit.Fit.NO, KitFit.fit(k1, 6)); assertEquals(KitFit.Fit.UNKNOWN, KitFit.fit(k1, 16))
        assertEquals("chest: the known fit first, the unknown offered, the refused left out",
            listOf("Light Armor Kit", "Unknown oil"), KitFit.forSlot(kits, 5).map { it.third.optString("name") })
        assertEquals("waist: the kit refused", listOf("Unknown oil", "Sharpening Stone"), KitFit.forSlot(kits, 6).map { it.third.optString("name") })
        assertEquals(KitFit.Fit.UNKNOWN, KitFit.fit(JSONObject().put("kfy", ",15,"), 5))      // ",15," is not slot 5
    }
}
