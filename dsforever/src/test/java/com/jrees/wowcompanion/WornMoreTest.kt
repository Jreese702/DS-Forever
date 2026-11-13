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
            "The game did not take it", "Choose", "Back")
        for (lang in listOf("es", "fr")) {
            val plain = JSONObject(java.io.File("../companion/app/src/main/assets/companion/i18n/$lang.json").readText()).getJSONObject("plain")
            for (w in words) assertTrue("$lang: $w", plain.has(w))
        }
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
