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
            "The game did not take it", "Choose", "Back", "Enchant", "Nothing fits", "No kits", "Equip",
            // bags-gear1: the slide-out's and the new Y menu's words
            "Swap", "Bind to a Button", "Window open", "Stat changes")
        for (lang in listOf("es", "fr")) {
            val plain = JSONObject(java.io.File("../companion/app/src/main/assets/companion/i18n/$lang.json").readText()).getJSONObject("plain")
            for (w in words) assertTrue("$lang: $w", plain.has(w))
        }
    }

    // bags-gear1 (the user, 2026-11-16: "The Y text menu stays for enchant and bind (use-effect items)."): Y = Enchant, + Bind for an
    // on-use piece; Unequip and Replace are X's slide-out now (SlideOutTest)
    private fun y(slot: Int = 5, worn: Boolean = true, use: Boolean = false, combat: Boolean = false, kits: Int = 1, fit: Int = kits,
                  window: Boolean = false, bind: Boolean = true) = WornMore.yRows(slot, worn, use, combat, kits, fit, window, bind)

    @Test fun yIsEnchantAloneForAPlainPiece() {
        val rows = y()
        assertEquals(listOf("Enchant"), rows.map { it.label })
        assertTrue(rows[0].usable)
        assertTrue("Enchant alone: Y opens the kit list at once", WornMore.kitsAtOnce(rows))
    }

    @Test fun yAddsBindForAPieceWithAUseEffect() {
        val rows = y(slot = 13, use = true)
        assertEquals(listOf("Enchant", "Bind to a Button"), rows.map { it.label })
        assertTrue(rows.all { it.usable })
        assertFalse("two rows: the menu", WornMore.kitsAtOnce(rows))
    }

    @Test fun noYMenuOnAnEmptySlotOrAmmo() {
        assertTrue(y(worn = false).isEmpty())
        assertTrue(y(slot = 0).isEmpty())
        assertTrue(y(slot = 20).isEmpty())
        assertFalse(WornMore.kitsAtOnce(emptyList()))
    }

    @Test fun yRowsSayWhyNot() {
        assertEquals("No kits", y(kits = 0)[0].why)
        assertEquals("Nothing fits", y(kits = 3, fit = 0)[0].why)
        assertEquals("Window open", y(window = true)[0].why)               // char-more2: the kit's cell would sell / deposit it
        assertTrue("a fight does not stop a kit (the game judges)", y(combat = true)[0].usable)
        assertEquals("In combat", y(use = true, combat = true)[1].why)       // the Bind box never opens in a fight
        assertEquals("Unavailable", y(use = true, bind = false)[1].why)      // an older add-on: no Bind arm
        assertTrue("a disabled Enchant still leaves a menu", WornMore.kitsAtOnce(y(kits = 0)))
    }

    @Test fun theBindAnswersInTerseWords() {
        assertEquals("Try again", WornMore.say(false, "busy"))
        assertEquals("Try again", WornMore.say(false, "lost"))
        assertEquals("Unavailable", WornMore.say(false, "unsupported"))
        assertEquals("In combat", WornMore.say(false, "combat"))
        assertNull(WornMore.say(true, ""))
    }

    @Test fun theModelReadsTheC19Cells() {
        val m = CharacterModel()
        m.update(JSONObject().put("type", "character").put("c", JSONObject().put("eqs", JSONObject()
            .put("row", "auracancel").put("wb", 9).put("ba", 10).put("bb", 11).put("box", true).put("ask", false))))
        val p = m.setPad!!
        assertEquals(9, p.bindArm); assertEquals(10, p.boxA); assertEquals(11, p.boxB); assertTrue(p.box)
        m.update(JSONObject().put("type", "character").put("c", JSONObject().put("eqs", JSONObject().put("row", "auracancel").put("ask", false))))
        assertNull(m.setPad!!.bindArm); assertFalse(m.setPad!!.box)                  // an older add-on: no Bind, no box
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

    /** b-fix (the user on 1462: "pressing B in the more menu closes the character page and leaves the menu open."): B goes Home only when
     *  nothing of the room's own is open, or B still reaches the game's window (the pass refused in a fight); never over the game's question */
    @Test fun bLeadsHomeOnlyWithNothingOpen() {
        assertTrue(WornMore.bLeadsHome(open = false, gameAsks = false, bToGame = false))
        assertFalse(WornMore.bLeadsHome(open = true, gameAsks = false, bToGame = false))      // the menu / a list / the question: one step back
        assertTrue(WornMore.bLeadsHome(open = true, gameAsks = false, bToGame = true))        // a fight: B still closes the game's window
        assertFalse(WornMore.bLeadsHome(open = false, gameAsks = true, bToGame = false))      // the game's question: B answers it
        assertFalse(WornMore.bLeadsHome(open = true, gameAsks = true, bToGame = true))
    }

    @Test fun theModelReadsTheC18BFlags() {
        val m = CharacterModel()
        m.update(JSONObject().put("type", "character").put("c", JSONObject().put("eqs", JSONObject()
            .put("row", "auracancel").put("more", true).put("gask", true).put("bgo", true).put("ask", false))))
        assertTrue(m.setPad!!.gameAsks); assertTrue(m.setPad!!.bToGame)
        m.update(JSONObject().put("type", "character").put("c", JSONObject().put("eqs", JSONObject().put("row", "auracancel").put("ask", false))))
        assertFalse(m.setPad!!.gameAsks); assertFalse(m.setPad!!.bToGame)               // an older add-on: B as before
    }
}
