package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * pk1 (the user, 2026-11-12: "we need to make a way to skip a polestar objective, idealy in the home map moduale with X or Y, maybe we
 * also add a previous step button? idk"; the picks: X Skip, Y Previous = "Undo my last skip", no confirm, Home's map + the Map room +
 * the pause wheel): the step doc's sk / u, the taps, the light blue row's X / Y and hints (merged with a quest and not), the Map room's
 * Polestar row and the pause wheel's two slices.
 */
class PolestarSkipTest {
    private fun step(sk: Boolean, u: Int, q: Int = 0) = PolestarStep.parse(JSONObject(
        """{"type":"pstep","v":"ps6","t":"Wolves","g":"Kill wolves","q":$q,"m":1429,"x":0.4,"y":0.6${if (sk) ",\"sk\":true,\"u\":$u" else ""}}"""))

    @Test fun theStepDocSaysSkipAndHowManyCanBeUndone() {
        val s = step(sk = true, u = 2)!!
        assertTrue(s.skip); assertEquals(2, s.undo)
        val old = step(sk = false, u = 0)!!                                  // an older add-on: no sk, no u
        assertFalse(old.skip); assertEquals(0, old.undo)
        assertTrue(PolestarSkip.canSkip(true, s)); assertTrue(PolestarSkip.canPrev(true, s))
        assertFalse(PolestarSkip.canSkip(true, old)); assertFalse(PolestarSkip.canPrev(true, old))
        assertFalse("nothing to undo", PolestarSkip.canPrev(true, step(sk = true, u = 0)))
        assertFalse("not shown (another setting)", PolestarSkip.canSkip(false, s))
        assertFalse(PolestarSkip.canSkip(true, null))
    }

    @Test fun theTapsAre38Then10Or11() {
        assertEquals(listOf(38, 10), PolestarSkip.cols(prev = false))
        assertEquals(listOf(38, 11), PolestarSkip.cols(prev = true))
        // never the row's other words: 8 / 9 the row holds, 0-7 the automation mask
        assertTrue(PolestarSkip.SKIP !in 0..9 && PolestarSkip.PREV !in 0..9)
        assertEquals(PolestarNav.PS_SAY, PolestarSkip.SAY)
    }

    private val g = emptyList<Pair<String, List<Int>>>()

    @Test fun xSkipsAndYGoesBackOnTheLightBlueRow() {
        val plain = MapList.stops(MapList.items(true, listOf(7, 8), g, polestar = true) { false })
        val c = MapCursor(); c.touched(MapList.POLESTAR, plain)
        assertEquals(MapCursor.Do.PsSkip, c.key("PAD3", plain, MapList.POLESTAR, false, psSkip = true, psPrev = true))
        assertEquals(MapCursor.Do.PsPrev, c.key("PAD4", plain, MapList.POLESTAR, false, psSkip = true, psPrev = true))
        assertEquals(MapCursor.Do.Polestar, c.key("PAD1", plain, MapList.POLESTAR, false, psSkip = true, psPrev = true))
        // nothing to undo: Y does nothing; no Skip (an older add-on): X does nothing
        assertEquals(MapCursor.Do.Nothing, c.key("PAD4", plain, MapList.POLESTAR, false, psSkip = true, psPrev = false))
        assertEquals(MapCursor.Do.Nothing, c.key("PAD3", plain, MapList.POLESTAR, false, psSkip = false, psPrev = false))
    }

    @Test fun mergedWithAQuestXAndYAreStillSkipAndPrevious() {
        val merged = MapList.stops(MapList.items(true, listOf(7, 8), g, polestar = true, merged = 7) { false })
        val c = MapCursor(); c.touched(MapList.POLESTAR, merged)
        assertEquals(MapCursor.Do.PsSkip, c.key("PAD3", merged, MapList.POLESTAR, false, psSkip = true, psPrev = true))
        assertEquals(MapCursor.Do.PsPrev, c.key("PAD4", merged, MapList.POLESTAR, false, psSkip = true, psPrev = true))
        // that quest's Track / Open are not on X / Y any more
        for (k in listOf("PAD3", "PAD4")) {
            val d = c.key(k, merged, MapList.POLESTAR, false, psSkip = true, psPrev = true)
            assertFalse(d is MapCursor.Do.Untrack || d is MapCursor.Do.Watch || d is MapCursor.Do.Open)
        }
        // another quest's row keeps its own X / Y
        c.touched(8, merged)
        assertEquals(MapCursor.Do.Untrack(8), c.key("PAD3", merged, MapList.POLESTAR, false, psSkip = true, psPrev = true))
        assertEquals(MapCursor.Do.Open(8), c.key("PAD4", merged, MapList.POLESTAR, false, psSkip = true, psPrev = true))
    }

    @Test fun theRowsHintsSayOnlyWhatWorks() {
        assertEquals(listOf("a" to "Unfocus", "x" to "Skip step", "y" to "Previous step"), PolestarSkip.rowHints(true, true, true))
        assertEquals(listOf("a" to "Focus", "x" to "Skip step"), PolestarSkip.rowHints(false, true, false))   // nothing to undo: no Y
        assertEquals(listOf("a" to "Unfocus"), PolestarSkip.rowHints(true, false, false))                    // an older add-on
        // merged or not, never the quest's Track / Open
        val words = PolestarSkip.rowHints(true, true, true).map { it.second }
        assertFalse(words.any { it == "Track" || it == "Untrack" || it == "Open" })
    }

    @Test fun theMapRoomsPolestarRowTakesXAndY() {
        val book = MapQuestBook()
        val log = listOf(MapQuestBook.Entry(7, "Elwynn Forest"))
        assertEquals(MapQuestBook.Row.Polestar, book.rows(log, polestar = true).first())
        assertFalse(book.rows(log).contains(MapQuestBook.Row.Polestar))
        assertEquals(MapRoomKeys.PsDo.SKIP, MapRoomKeys.polestarKey("PAD3", true to true))
        assertEquals(MapRoomKeys.PsDo.PREV, MapRoomKeys.polestarKey("PAD4", true to true))
        assertEquals(MapRoomKeys.PsDo.NOTHING, MapRoomKeys.polestarKey("PAD4", true to false))     // nothing to undo: not Options
        assertEquals(MapRoomKeys.PsDo.NOTHING, MapRoomKeys.polestarKey("PAD3", null))
        assertEquals(MapRoomKeys.PsDo.NOTHING, MapRoomKeys.polestarKey("PAD1", true to true))
        assertEquals(MapRoomKeys.PsDo.PASS, MapRoomKeys.polestarKey("PADDDOWN", true to true))      // the list still moves
        assertEquals(MapRoomKeys.PsDo.PASS, MapRoomKeys.polestarKey("PAD2", true to true))
    }

    private fun page3(skip: Boolean, prev: Boolean): HomePauseModel.Flow {
        val f = HomePauseModel.Flow()
        f.psSkip = skip; f.psPrev = prev
        f.turn(+1)                                                      // the centre page -> page 3
        return f
    }
    private val up = HomePauseModel.fromGame(2)                          // N: Skip step
    private val upLeft = HomePauseModel.fromGame(3)                      // NW: Previous step

    @Test fun thePauseWheelHasSkipAndPreviousOnPage3() {
        val f = page3(skip = true, prev = true)
        assertEquals(PolestarSkip.KEY_SKIP, f.slices()[up]?.key)
        assertEquals(PolestarSkip.KEY_PREV, f.slices()[upLeft]?.key)
        val a = f.touch(up) as HomePauseModel.Act.Pick
        assertEquals(PolestarSkip.KEY_SKIP, a.slice.key); assertEquals(HomePauseModel.Kind.HOME, a.slice.kind)
        assertEquals("Skip step", a.slice.label)
        val b = f.touch(upLeft) as HomePauseModel.Act.Pick
        assertEquals(PolestarSkip.KEY_PREV, b.slice.key); assertEquals("Previous step", b.slice.label)
        // the other slices of the page are where they were
        assertEquals("calendar", f.slices()[HomePauseModel.fromGame(1)]?.key)
        assertEquals("pvp", f.slices()[HomePauseModel.fromGame(4)]?.key)
    }

    @Test fun previousIsGreyedWithNothingToUndoAndBothHideWithNoStep() {
        val f = page3(skip = true, prev = false)
        val p = f.slices()[upLeft]!!
        assertFalse(p.enabled)
        val r = f.touch(upLeft) as HomePauseModel.Act.Refuse
        assertEquals("Nothing to undo", r.slice.offLine)
        assertTrue(f.slices()[up]!!.enabled)
        val none = page3(skip = false, prev = false)
        assertNull(none.slices()[up]); assertNull(none.slices()[upLeft])
        assertEquals(HomePauseModel.Act.None, none.touch(up))
        // the stick lights nothing on a hidden slot
        assertEquals(HomePauseModel.Act.None, none.vector(0.0, 1.0)); assertEquals(-1, none.pointed)
        // the centre page is unchanged
        val c = HomePauseModel.Flow(); c.psSkip = true; c.psPrev = true
        assertFalse(c.slices().any { it?.key == PolestarSkip.KEY_SKIP || it?.key == PolestarSkip.KEY_PREV })
    }

    @Test fun aRefusedPressSaysWhyTersely() {
        assertEquals("After combat", PolestarSkip.refusedLine("combat"))
        assertEquals("Nothing to undo", PolestarSkip.refusedLine("nothing"))
        assertEquals("No Polestar step", PolestarSkip.refusedLine("no Polestar"))
        assertNull(PolestarSkip.refusedLine("weird"))
    }
}
