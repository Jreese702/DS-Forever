package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * loot-pop (the user, 2026-11-16: "I have also noticed that the loot roll window is delayed and buggy, sometimes making me press the
 * button twice to move on or even just taking like 2 second after accepting an input to move on"): ONE PRESS = ONE ROLL, the next one at
 * once, the game's word correcting it; the game's bind confirm on its roll, answered the moment it is known.
 */
class RollBookTest {
    private fun roll(id: Int, bop: Boolean = false, ask: String? = null, need: Boolean = true, greed: Boolean = true, de: Boolean = false,
                     sel: Boolean = false) = RollBook.Roll(JSONObject().put("id", id).put("name", "Item $id").put("q", 2)
        .put("need", need).put("greed", greed).put("de", de).put("bop", bop).put("sel", sel).apply { if (ask != null) put("ask", ask) }
        .put("cells", JSONObject().put("need", id * 10 + 1).put("greed", id * 10 + 2).put("pass", id * 10).put("de", id * 10 + 3)))

    private fun ask(id: Int) = RollBook.Ask(id, "Looting Item $id will bind it to you.", 6, 7, "Okay", "Cancel")
    private fun ids(b: RollBook) = b.shown().map { it.id }

    @Test fun anAnswerLeavesAtOnceAndTheNextShows() {
        val b = RollBook()
        b.onDoc(listOf(roll(1), roll(2), roll(3)), emptyMap(), 1_000)
        assertTrue(b.answer(1, "need", 1_100))
        assertEquals("gone from the screen at once, the next ones shown", listOf(2, 3), ids(b))
        // a document written before the game had the answer still lists it: it stays away
        assertTrue(b.onDoc(listOf(roll(1), roll(2), roll(3)), emptyMap(), 1_300).isEmpty())
        assertEquals(listOf(2, 3), ids(b))
        // the game took it (d34: gone from the document): forgotten
        b.onDoc(listOf(roll(2), roll(3)), emptyMap(), 1_400)
        assertEquals(listOf(2, 3), ids(b))
        assertTrue(b.taps().isEmpty())
    }

    @Test fun aRollTheGameDidNotTakeComesBack() {
        val b = RollBook()
        b.onDoc(listOf(roll(1), roll(2)), emptyMap(), 1_000)
        b.answer(1, "greed", 1_000)
        assertTrue(b.onDoc(listOf(roll(1), roll(2)), emptyMap(), 1_000 + RollBook.REFUSE_MS - 1).isEmpty())
        assertEquals(listOf(2), ids(b))
        assertEquals(listOf(1), b.onDoc(listOf(roll(1), roll(2)), emptyMap(), 1_000 + RollBook.REFUSE_MS))
        assertEquals("still offered well after the answer: back, open", listOf(1, 2), ids(b))
        assertEquals(RollBook.Row.OPEN, b.rowOf(b.shown()[0]))
    }

    /** THE DOUBLE PRESS: two answers in a row are never locked out (the old SETTLE 700 ms + LOCKOUT 500 ms); only a bounce is */
    @Test fun noLockBetweenTwoAnswersOnlyABounce() {
        val b = RollBook()
        b.onDoc(listOf(roll(1), roll(2), roll(3)), emptyMap(), 1_000)
        assertTrue(b.answer(1, "need", 2_000))
        b.onDoc(listOf(roll(2), roll(3)), emptyMap(), 2_200)                 // the roll set changed: no lock follows
        assertFalse("a bounce", b.mayAnswer(2_000 + RollBook.GAP_MS - 1))
        assertTrue("the next press, 250 ms after the first (was refused: One moment)", b.mayAnswer(2_250))
        assertTrue(b.answer(2, "pass", 2_250))
        assertEquals(listOf(3), ids(b))
    }

    @Test fun cannotAnswerWhatTheGameDoesNotAllow() {
        val b = RollBook()
        b.onDoc(listOf(roll(1, need = false), roll(2)), emptyMap(), 1_000)
        assertFalse(b.answer(1, "need", 1_000))
        assertFalse(b.answer(1, "de", 1_000))
        assertFalse(b.answer(9, "pass", 1_000))
        assertTrue(b.answer(1, "pass", 1_000))
        assertFalse("answered: not again", b.answer(1, "pass", 1_500))
    }

    @Test fun theGamesNextSelection() {
        // GamepadGroupLootRollFrame.RemoveRoll: the one before, else the first
        assertEquals(2, RollBook.nextAfter(listOf(1, 2, 3), 1))
        assertEquals(1, RollBook.nextAfter(listOf(1, 2, 3), 2))
        assertEquals(2, RollBook.nextAfter(listOf(1, 2, 3), 3))
        assertNull(RollBook.nextAfter(listOf(1), 1))
        assertEquals(1, RollBook.nextAfter(listOf(1, 2), 7))
    }

    /** a BoP Need: the game's confirm on the roll at once; Okay taps the pop-up's own cell the moment it is known */
    @Test fun theBindConfirmShowsAtOnceAndOkayGoesWhenKnown() {
        val b = RollBook()
        b.onDoc(listOf(roll(5, bop = true), roll(6)), emptyMap(), 1_000)
        assertTrue(b.answer(5, "need", 1_000))
        assertEquals("the roll stays, showing the game's confirm", RollBook.Row.CONFIRM, b.rowOf(b.shown()[0]))
        assertEquals(listOf(5, 6), ids(b))
        // the player's Okay comes BEFORE the add-on's document carries the pop-up: it waits for it, never lost
        assertTrue(b.confirm(5, ok = true, gt = 1_150))
        assertEquals("Okay: gone at once", listOf(6), ids(b))
        assertTrue(b.taps().isEmpty())
        assertTrue(b.waiting(5))
        // the document with the game's pop-up (d34: ask on the roll, the dialog naming it): the Okay goes now, on the pop-up's cell
        b.onDoc(listOf(roll(5, bop = true, ask = "need"), roll(6)), mapOf(5 to ask(5)), 1_250)
        assertEquals(listOf(RollBook.Tap(5, "idlg", 6, "confirm ok")), b.taps())
        assertTrue(b.taps().isEmpty())                                       // once
        assertEquals(listOf(6), ids(b))
        // ConfirmLootRoll: the add-on drops the roll
        b.onDoc(listOf(roll(6)), emptyMap(), 1_400)
        assertEquals(listOf(6), ids(b))
        assertTrue(b.taps().isEmpty())
    }

    /** review: the game's bind confirm is ONE exclusive pop-up, reused for the next ask - a waiting Okay never goes once another
     *  bind-on-pickup roll was answered after it (that roll may own the pop-up when the tap lands: our Okay would bind IT) */
    @Test fun aWaitingOkayNeverGoesToAnotherRollsPopup() {
        val b = RollBook()
        b.onDoc(listOf(roll(5, bop = true), roll(6, bop = true)), emptyMap(), 1_000)
        assertTrue(b.answer(5, "need", 1_000))
        assertTrue(b.confirm(5, ok = true, gt = 1_150))                      // Okay before the pop-up was known: it waits
        assertTrue(b.answer(6, "need", 1_300))                                // a Need on the next BoP roll: the game asks about 6 next
        // the document written before 6's Need landed: the pop-up still 5's - but 6's tap is on its way and takes the pop-up
        b.onDoc(listOf(roll(5, bop = true, ask = "need"), roll(6, bop = true)), mapOf(5 to ask(5)), 1_350)
        assertTrue("never Okay on a pop-up another roll may own by now", b.taps().isEmpty())
        assertFalse(b.waiting(5))
        // the pop-up 6's: its confirm on 6; 5 comes back (not taken) once the game's word is due
        b.onDoc(listOf(roll(5, bop = true), roll(6, bop = true, ask = "need")), mapOf(6 to ask(6)), 1_150 + RollBook.REFUSE_MS)
        assertEquals(RollBook.Row.OPEN, b.rowOf(b.shown().first { it.id == 5 }))
        assertEquals(RollBook.Row.CONFIRM, b.rowOf(b.shown().first { it.id == 6 }))
        assertTrue(b.taps().isEmpty())
    }

    @Test fun okayWithThePopupKnownTapsAtOnce() {
        val b = RollBook()
        b.onDoc(listOf(roll(5, bop = true, ask = "greed")), mapOf(5 to ask(5)), 1_000)
        assertEquals("the game asks (its own frame's press): the confirm", RollBook.Row.CONFIRM, b.rowOf(b.shown()[0]))
        assertTrue(b.confirm(5, ok = true, gt = 1_050))
        assertEquals(listOf(RollBook.Tap(5, "idlg", 6, "confirm ok")), b.taps())
        assertTrue(ids(b).isEmpty())
    }

    @Test fun cancelOpensTheRollAgain() {
        val b = RollBook()
        b.onDoc(listOf(roll(5, bop = true)), emptyMap(), 1_000)
        b.answer(5, "greed", 1_000)
        b.onDoc(listOf(roll(5, bop = true, ask = "greed")), mapOf(5 to ask(5)), 1_200)
        assertTrue(b.confirm(5, ok = false, gt = 1_300))
        assertEquals(listOf(RollBook.Tap(5, "idlg", 7, "confirm cancel")), b.taps())
        assertEquals(RollBook.Row.OPEN, b.rowOf(b.shown()[0]))
        // the pop-up still in a document on its way: still open (no flicker back to the confirm)
        b.onDoc(listOf(roll(5, bop = true, ask = "greed")), mapOf(5 to ask(5)), 1_350)
        assertEquals(RollBook.Row.OPEN, b.rowOf(b.shown()[0]))
        b.onDoc(listOf(roll(5, bop = true)), emptyMap(), 1_500)
        assertEquals(RollBook.Row.OPEN, b.rowOf(b.shown()[0]))
        assertTrue("answerable again", b.answer(5, "pass", 1_600))
    }

    @Test fun anOkayTheGameDidNotTakeShowsTheConfirmAgain() {
        val b = RollBook()
        b.onDoc(listOf(roll(5, bop = true, ask = "need")), mapOf(5 to ask(5)), 1_000)
        b.confirm(5, ok = true, gt = 1_000)
        b.taps()
        assertEquals(listOf(5), b.onDoc(listOf(roll(5, bop = true, ask = "need")), mapOf(5 to ask(5)), 1_000 + RollBook.REFUSE_MS))
        assertEquals(RollBook.Row.CONFIRM, b.rowOf(b.shown()[0]))
    }

    /** out of a fight the controller's press is the game's first: the cell only when a document a moment later still offers the roll */
    @Test fun theControllersPressIsTheGamesFirst() {
        val b = RollBook()
        b.onDoc(listOf(roll(1, sel = true), roll(2)), emptyMap(), 1_000)
        assertTrue(b.answer(1, "need", 1_000, gameFirst = true))
        assertEquals(listOf(2), ids(b))
        b.onDoc(listOf(roll(1), roll(2)), emptyMap(), 1_000 + RollBook.PAD_FALLBACK_MS - 1)
        assertTrue(b.taps().isEmpty())
        b.onDoc(listOf(roll(1), roll(2)), emptyMap(), 1_000 + RollBook.PAD_FALLBACK_MS)
        assertEquals(listOf(RollBook.Tap(1, "isell", 11, "roll need (the game did not take the controller's)")), b.taps())
        b.onDoc(listOf(roll(1), roll(2)), emptyMap(), 1_000 + RollBook.PAD_FALLBACK_MS + 100)
        assertTrue("once", b.taps().isEmpty())
        assertEquals(listOf(2), ids(b))
        // the game took the press itself: no tap at all
        val g = RollBook()
        g.onDoc(listOf(roll(1, sel = true), roll(2)), emptyMap(), 1_000)
        g.answer(1, "pass", 1_000, gameFirst = true)
        g.onDoc(listOf(roll(2)), emptyMap(), 1_200)
        g.onDoc(listOf(roll(2)), emptyMap(), 1_600)
        assertTrue(g.taps().isEmpty())
    }

    @Test fun theControllersOkayIsTheGamesFirstToo() {
        val b = RollBook()
        b.onDoc(listOf(roll(5, bop = true, ask = "need")), mapOf(5 to ask(5)), 1_000)
        b.confirm(5, ok = true, gt = 1_000, gameFirst = true)
        assertTrue(b.taps().isEmpty())
        b.onDoc(listOf(roll(5, bop = true, ask = "need")), mapOf(5 to ask(5)), 1_000 + RollBook.PAD_FALLBACK_MS)
        assertEquals(listOf(RollBook.Tap(5, "idlg", 6, "confirm ok")), b.taps())
        // the game answered it itself: nothing
        val g = RollBook()
        g.onDoc(listOf(roll(5, bop = true, ask = "need")), mapOf(5 to ask(5)), 1_000)
        g.confirm(5, ok = true, gt = 1_000, gameFirst = true)
        g.onDoc(emptyList(), emptyMap(), 1_200)
        g.onDoc(emptyList(), emptyMap(), 1_600)
        assertTrue(g.taps().isEmpty())
    }

    @Test fun anOkayTheGameNeverAskedForIsDropped() {
        val b = RollBook()
        b.onDoc(listOf(roll(5, bop = true)), emptyMap(), 1_000)
        b.answer(5, "need", 1_000)
        b.confirm(5, ok = true, gt = 1_100)
        b.onDoc(listOf(roll(5, bop = true)), emptyMap(), 1_100 + RollBook.WANT_WAIT_MS)
        assertTrue(b.taps().isEmpty())
        assertEquals("never asked, still offered: back, open", RollBook.Row.OPEN, b.rowOf(b.shown()[0]))
    }

    /** the small panel's and the confirm's own words in Spanish and French (the game's words never go through Tr) */
    @Test fun theNewWordsAreTranslated() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { java.io.File(it) }.first { it.isDirectory }
        Tr.load("es", java.io.File(dir, "es.json").readText())
        Tr.load("fr", java.io.File(dir, "fr.json").readText())
        assertEquals("Se liga al recogerlo", Tr.t("Binds when picked up", "es"))
        assertEquals("Lié quand ramassé", Tr.t("Binds when picked up", "fr"))
        assertEquals("Desencantar", Tr.t("Disenchant", "es"))
        assertEquals("Désenchanter", Tr.t("Disenchant", "fr"))
        assertEquals("+2 más", Tr.t("+2 more", "es"))
        assertEquals("+2 de plus", Tr.t("+2 more", "fr"))
        assertEquals("Tiradas de botín (3)", Tr.t("Loot rolls (3)", "es"))
    }

    @Test fun theConfirmsOfADialogsList() {
        val list = JSONArray()
            .put(JSONObject().put("i", 1).put("which", "CONFIRM_LOOT_ROLL").put("roll", 21).put("text", "Looting |cff0070ddX|r will bind it to you.")
                .put("buttons", JSONArray().put(JSONObject().put("k", 1).put("label", "Okay")).put(JSONObject().put("k", 2).put("label", "Cancel")))
                .put("cells", JSONObject().put("b1", 0).put("b2", 1)))
            .put(JSONObject().put("i", 2).put("which", "DEATH").put("cells", JSONObject().put("b1", 6)))
            .put(JSONObject().put("i", 3).put("which", "CONFIRM_LOOT_ROLL"))           // an older add-on: no roll named, a box as before
        val m = RollBook.asksOf(list)
        assertEquals(setOf(21), m.keys)
        assertEquals(0, m[21]!!.okCell); assertEquals(1, m[21]!!.cancelCell); assertEquals("Okay", m[21]!!.okLabel)
    }
}
