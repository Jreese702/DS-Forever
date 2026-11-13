package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * uneq1 (the user's picks, 2026-11-13: "Unequip lives in the Character room: choose a worn slot, Y = More opens a small menu with
 * 'Unequip' first (A does it, B back, Up/Down moves)"; "WEAPONS (slots 16,17,18) MAY be unequipped in combat, armor is refused in
 * combat"; "bags full -> row disabled 'Bags full'; ammo (slot 0) not offered"): the More menu's rules ([WornMore]) and the add-on's
 * c14 eqs part read by [CharacterModel].
 */
class WornMoreTest {
    @Test fun unequipIsTheFirstRow() {
        val rows = WornMore.rows(5, worn = true, combat = false, freeGeneral = 3, routed = true)
        assertEquals("Unequip", rows.first().label)
        assertTrue(rows.first().usable); assertNull(rows.first().why)
    }

    @Test fun ammoAndBadSlotsHaveNoMenu() {
        assertTrue(WornMore.rows(0, worn = true, combat = false, freeGeneral = 3, routed = true).isEmpty())
        assertTrue(WornMore.rows(20, worn = true, combat = false, freeGeneral = 3, routed = true).isEmpty())
    }

    @Test fun disabledRowsSayWhy() {
        fun why(slot: Int, worn: Boolean = true, combat: Boolean = false, free: Int? = 3, routed: Boolean = true) =
            WornMore.rows(slot, worn, combat, free, routed).first().let { assertFalse(it.usable); it.why }
        assertEquals("Empty", why(5, worn = false))
        assertEquals("In combat", why(5, combat = true))           // armour in a fight
        assertEquals("Bags full", why(5, free = 0))
        assertEquals("Unavailable", why(5, routed = false))
    }

    @Test fun weaponsStayUsableInAFight() {
        for (s in listOf(16, 17, 18)) assertTrue(WornMore.rows(s, worn = true, combat = true, freeGeneral = 1, routed = true).first().usable)
        // bags full still wins in a fight
        assertEquals("Bags full", WornMore.rows(16, worn = true, combat = true, freeGeneral = 0, routed = true).first().why)
    }

    @Test fun freeSlotsNotKnownYetLeaveItToTheAddOn() {
        assertTrue(WornMore.rows(5, worn = true, combat = false, freeGeneral = null, routed = true).first().usable)
    }

    @Test fun keys() {
        assertEquals(0 to WornMore.Act.NONE, WornMore.key(0, 1, "PADDUP"))
        assertEquals(0 to WornMore.Act.NONE, WornMore.key(0, 1, "PADDDOWN"))      // clamped to the one row
        assertEquals(1 to WornMore.Act.NONE, WornMore.key(0, 2, "PADDDOWN"))
        assertEquals(0 to WornMore.Act.CHOOSE, WornMore.key(0, 1, "PAD1"))
        assertEquals(0 to WornMore.Act.BACK, WornMore.key(0, 1, "PAD2"))
        assertEquals(0 to WornMore.Act.NONE, WornMore.key(0, 1, "PAD4"))
    }

    @Test fun theTwoTapsArmThenSlotColumn() {
        assertEquals(listOf("auracancel" to 7, "bmvq" to 15), WornMore.cells("auracancel", 7, 16))
    }

    @Test fun answersInTerseWords() {
        assertNull(WornMore.say(true, ""))
        assertEquals("Bags full", WornMore.say(false, "full"))
        assertEquals("In combat", WornMore.say(false, "combat"))
        assertEquals("Empty", WornMore.say(false, "empty"))
        assertEquals("Try again", WornMore.say(false, "settle"))
        assertEquals("Try again", WornMore.say(false, "late"))
        assertEquals("The game refused it", WornMore.say(false, "refused"))
    }

    // uneq2: the add-on re-sends its last ack in every eqs part; only an answer to this room's own tap is said
    @Test fun onlyAFreshAnswerIsSaid() {
        assertFalse("no tap sent", WornMore.fresh(0L, 10_000L))
        assertTrue("the answer at once", WornMore.fresh(10_000L, 10_050L))
        assertTrue("the 0.6 s confirm", WornMore.fresh(10_000L, 10_700L))
        assertFalse("an old ack minutes later", WornMore.fresh(10_000L, 200_000L))
        assertFalse("a clock before the tap", WornMore.fresh(10_000L, 9_000L))
    }

    @Test fun everyWordHasSpanishAndFrench() {
        val words = listOf("Unequip", "More", "Bags full", "In combat", "Empty", "Unavailable", "Try again", "The game refused it",
            "The game did not take it", "Choose", "Back", "Replace", "Enchant", "Nothing fits", "No kits", "Equip")
        for (lang in listOf("es", "fr")) {
            val plain = JSONObject(java.io.File("../companion/app/src/main/assets/companion/i18n/$lang.json").readText()).getJSONObject("plain")
            for (w in words) assertTrue("$lang: $w", plain.has(w))
        }
    }

    // char-more (the user, 2026-11-13: "we should have options there like unequip, replace and enchant")
    private fun fits(replace: Int = 2, now: Int = replace, routed: Boolean = true, kits: Int = 1, fit: Int = kits) =
        WornMore.Fits(replace, now, routed, kits, fit)

    @Test fun threeRowsUnequipReplaceEnchant() {
        val rows = WornMore.rows(5, worn = true, combat = false, freeGeneral = 3, routed = true, fits = fits())
        assertEquals(listOf("Unequip", "Replace", "Enchant"), rows.map { it.label })
        assertTrue(rows.all { it.usable && it.why == null })
        // the old call (no fits) keeps the one row
        assertEquals(listOf("Unequip"), WornMore.rows(5, worn = true, combat = false, freeGeneral = 3, routed = true).map { it.label })
    }

    @Test fun replaceAndEnchantSayWhyNot() {
        fun row(i: Int, slot: Int = 5, combat: Boolean = false, f: WornMore.Fits = fits(), free: Int? = 3) =
            WornMore.rows(slot, worn = true, combat = combat, freeGeneral = free, routed = true, fits = f)[i]
        assertEquals("Nothing fits", row(1, f = fits(replace = 0)).why)
        assertEquals("In combat", row(1, combat = true).why)                              // armour in a fight, as the game
        assertEquals("In combat", row(1, slot = 17, combat = true, f = fits(now = 0)).why)  // a weapon only the drag can place
        assertTrue(row(1, slot = 16, combat = true, f = fits(now = 1)).usable)              // a weapon the game's own equip lands there
        assertEquals("Unavailable", row(1, f = fits(routed = false)).why)
        assertTrue("bags full does not stop a swap", row(1, free = 0).usable)
        assertEquals("No kits", row(2, f = fits(kits = 0)).why)
        assertEquals("Nothing fits", row(2, f = fits(kits = 3, fit = 0)).why)
        val empty = WornMore.rows(5, worn = false, combat = false, freeGeneral = 3, routed = true, fits = fits())
        assertTrue(empty.none { it.usable }); assertTrue(empty.all { it.why == "Empty" })
    }

    @Test fun downReachesEveryRow() {
        assertEquals(2 to WornMore.Act.NONE, WornMore.key(1, 3, "PADDDOWN"))
        assertEquals(2 to WornMore.Act.NONE, WornMore.key(2, 3, "PADDDOWN"))
    }

    /** char-more2 (review): a vendor / bank / mail open: a kit's cell would sell / deposit it, so Enchant says why */
    @Test fun enchantWithAGameWindowOpen() {
        val r = WornMore.rows(5, worn = true, combat = false, freeGeneral = 3, routed = true,
            fits = WornMore.Fits(1, 1, true, kits = 2, kitsFit = 2, window = true))
        assertEquals(WornMore.ENCHANT, r[2].label); assertFalse(r[2].usable); assertEquals("Window open", r[2].why)
        assertTrue("Replace stays (the drag)", r[1].usable)
    }

    @Test fun theModelReadsTheC16ListCell() {
        val m = CharacterModel()
        m.update(JSONObject().put("type", "character").put("c", JSONObject().put("eqs", JSONObject()
            .put("row", "auracancel").put("mon", 6).put("moff", 7).put("msb", 8).put("more", true).put("msub", true).put("ask", false))))
        assertEquals(8, m.setPad!!.moreSub); assertTrue(m.setPad!!.sub); assertTrue(m.setPad!!.more)
        m.update(JSONObject().put("type", "character").put("c", JSONObject().put("eqs", JSONObject().put("row", "auracancel").put("ask", false))))
        assertNull(m.setPad!!.moreSub); assertFalse(m.setPad!!.sub)
    }

    @Test fun theModelReadsTheC14Cells() {
        val m = CharacterModel()
        m.update(JSONObject().put("type", "character").put("c", JSONObject().put("eqs", JSONObject()
            .put("row", "auracancel").put("yes", 3).put("no", 4).put("uq", 5).put("mon", 6).put("moff", 7).put("more", true)
            .put("ask", false).put("ack", JSONObject().put("seq", 2).put("op", "unequip").put("id", 16).put("ok", false).put("why", "combat")))))
        val p = m.setPad!!
        assertEquals(5, p.unequip); assertEquals(6, p.moreOn); assertEquals(7, p.moreOff); assertTrue(p.more)
        assertEquals("unequip", p.ack!!.op); assertEquals("combat", p.ack!!.why)
        // an older add-on (c13): no route, the menu shows Unavailable
        m.update(JSONObject().put("type", "character").put("c", JSONObject().put("eqs", JSONObject().put("row", "auracancel").put("ask", false))))
        assertNull(m.setPad!!.unequip); assertFalse(m.setPad!!.more)
    }
}
