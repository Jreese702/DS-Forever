package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** the user: "when I look around the bags at different items it lags a lot ... if I push two fast it gets a little lost" */
class BagsCursorTest {
    private val R = "PADDRIGHT"; private val L = "PADDLEFT"; private val U = "PADDUP"; private val D = "PADDDOWN"
    // the backpack (16), a 10-slot bag, a 22-slot bag: Bags.lua's layout order
    private val open = listOf(BagsCursor.Bag(0, 16), BagsCursor.Bag(1, 10), BagsCursor.Bag(2, 22))

    @Test fun theAddonsOwnRules() {
        assertEquals(0 to 2, BagsCursor.step(open, 0 to 1, R, false))
        assertEquals(1 to 1, BagsCursor.step(open, 0 to 16, R, false))          // the bag's end: the next bag's first slot
        assertEquals(0 to 16, BagsCursor.step(open, 1 to 1, L, false))
        assertEquals(0 to 13, BagsCursor.step(open, 0 to 3, D, false))
        assertEquals(0 to 16, BagsCursor.step(open, 0 to 7, D, false))          // the short last row: its last slot
        assertEquals(1 to 3, BagsCursor.step(open, 0 to 13, D, false))          // the bag's last row: the next bag, same column
        assertEquals(0 to 13, BagsCursor.step(open, 1 to 3, U, false))          // up into the bag above, same column
        assertEquals(0 to 16, BagsCursor.step(open, 1 to 9, U, false))          // ... its last row is short: its last slot
        assertNull(BagsCursor.step(open, 0 to 4, U, false))                     // the top row: onto the strip (not guessed)
        assertEquals(0 to 4, BagsCursor.step(open, 0 to 4, U, true))            // ... not with an item in the hand
        assertEquals(2 to 22, BagsCursor.step(open, 2 to 22, R, false))         // the very end stays
        assertEquals(1 to 2, BagsCursor.step(listOf(BagsCursor.Bag(1, 10)), 0 to 5, R, false))   // its bag closed: the first open bag
    }

    private fun bagsDoc(): JSONObject {
        val bags = JSONArray(); val items = JSONArray()
        for (b in open) bags.put(JSONObject().put("bag", b.id).put("slots", b.slots))
        for (s in 1..16) items.put(JSONObject().put("bag", 0).put("slot", s).put("id", 1000 + s).put("name", "Item $s"))
        return JSONObject().put("bags", bags).put("items", items)
    }
    private fun ctl(seq: Int, slot: Int, tipKey: String? = null) = JSONObject().put("on", true).put("mode", "grid").put("seq", seq)
        .put("cur", JSONObject().put("bag", 0).put("slot", slot)).apply { if (tipKey != null) put("tip", JSONObject().put("key", tipKey)) }

    @Test fun fiveMovesIn300msLandFiveCellsOverAtOnce() {
        val st = BagsView.State()
        st.set(bagsDoc()); st.ctl = ctl(40, 1, "bag 0 1 1001"); st.feed(0)
        var t = 0L
        repeat(5) { assertTrue(st.predict.press(R, t)); t += 60 }                 // five presses in 300 ms, before any answer
        assertEquals(0 to 6, st.ctlCur())
        st.follow()
        assertEquals(1006, st.selected()!!.optInt("id"))                         // the detail: the final cell, from the bags already here
        // the game's tooltip still names the FIRST slot: dropped by its key, never drawn for the cursor's item
        assertNull(BagsView.tipOfSlot(st.ctl!!.optJSONObject("tip"), "bag 0 6 1006"))
        // the game answers two of them (one document for a frame), then the rest: the cursor never steps back
        st.ctl = ctl(42, 3); st.feed(320)
        assertEquals(0 to 6, st.ctlCur()); assertEquals(3, st.predict.pending)
        st.ctl = ctl(45, 6); st.feed(400)
        assertEquals(0 to 6, st.ctlCur()); assertEquals(0, st.predict.pending)
        // the game's tooltip for the slot the cursor rests on: shown
        st.ctl = ctl(45, 6, "bag 0 6 1006"); st.feed(520)
        assertEquals("bag 0 6 1006", BagsView.tipOfSlot(st.ctl!!.optJSONObject("tip"), "bag 0 6 1006")!!.optString("key"))
    }

    @Test fun theGameWinsOnAMismatch() {
        val p = BagsCursor.Predictor()
        p.game(true, 10, 0 to 1, open, false, 0)
        p.press(R, 0); p.press(R, 10); p.press(R, 20)
        assertEquals(0 to 4, p.shown)
        // the game took one press and put the cursor elsewhere (the bags changed under it): the other two replay from ITS cursor
        p.game(true, 11, 0 to 9, open, false, 50)
        assertEquals(0 to 11, p.shown)
        p.game(true, 13, 0 to 12, open, false, 90)                               // all answered: the game's own cursor, whatever it says
        assertNull(p.shown)
    }

    @Test fun pressesItDoesNotGuessWaitForTheGame() {
        val p = BagsCursor.Predictor()
        p.game(true, 5, 0 to 1, open, false, 0)
        p.press(R, 0)
        assertFalse(p.press("PAD1", 10))                                          // A: picked up by the game, not guessed
        assertFalse(p.press(R, 20))                                               // and nothing after it until the game catches up
        assertEquals(0 to 2, p.shown)
        p.game(true, 7, 0 to 2, open, true, 60)
        assertNull(p.shown); assertTrue(p.press(R, 70))                           // caught up: guessing again
        // off the grid (a menu, the strip, handed back): no guess at all
        p.game(false, 8, 0 to 3, open, true, 80)
        assertFalse(p.press(R, 90)); assertNull(p.shown)
    }

    @Test fun noAnswerDropsTheGuess() {
        val p = BagsCursor.Predictor()
        p.game(true, 1, 0 to 1, open, false, 0)
        p.press(R, 100)
        assertFalse(p.expire(100 + BagsCursor.STALE_MS - 1))
        assertTrue(p.expire(100 + BagsCursor.STALE_MS)); assertNull(p.shown)
    }

    @Test fun theHoverWaitOnlyOnce() {
        val f = BagsCursor.WarmFade(delay = 250, fade = 160)
        assertEquals(0, f.alpha("a", 0)); assertEquals(0, f.alpha("a", 200))      // the first tooltip waits the game's quarter second
        assertEquals(255, f.alpha("a", 500))
        assertEquals(255, f.alpha("b", 520))                                      // the next item: at once
        f.alpha(null, 600)                                                        // an empty slot between
        assertEquals(255, f.alpha("c", 700))
        f.alpha(null, 800)
        assertEquals(0, f.alpha("d", 800 + BagsCursor.WarmFade.WARM_MS + 1))     // a long look at nothing: it waits again
        f.cold(); assertEquals(0, f.alpha("e", 99_999))
    }

    /**
     * THE LONGEST TOOLTIP (the user: "the tooltip on some of the gear in my bag is too big, so I can't see the button glyph options"):
     * the row of glyphs is never covered and never leaves the room, however tall the tooltip.
     */
    @Test fun theGlyphRowAlwaysShows() {
        val top = 250f - 90f; val bottom = 1072f; val plate = 62f
        fun check(natural: Float) {
            val f = BagsCursor.fit(top, bottom, 600f, plate) { s -> natural * s }
            val rowTop = f.y + f.h; val rowBottom = rowTop + plate
            assertTrue("row inside the room ($natural)", f.y >= top && rowBottom <= bottom + 0.01f)
            assertTrue(f.scale >= BagsCursor.MIN_SCALE && f.scale <= 1f)
            if (natural + plate <= bottom - top) { assertEquals(1f, f.scale); assertEquals(0f, f.scrollMax) }
            else if (natural * BagsCursor.MIN_SCALE + plate <= bottom - top) assertEquals(0f, f.scrollMax)   // shrunk to fit
            else assertEquals(natural * f.scale - f.h, f.scrollMax, 0.5f)          // cut at the foot, the rest scrolls
        }
        for (h in listOf(200f, 700f, 850f, 1100f, 1300f, 2400f)) check(h)
        // a short tooltip stays level with the cursor's row
        assertEquals(600f, BagsCursor.fit(top, bottom, 600f, plate) { 300f * it }.y)
    }

    /** the user: "Right stick": a cut tooltip scrolls with it, read only; never the cursor */
    @Test fun theRightStickScrollsACutTooltip() {
        val dz = BagsCursor.STICK_DEADZONE
        assertEquals(100f, BagsCursor.stickScroll(100f, 500f, dz, 16), 0f)             // inside the dead-zone: still
        assertEquals(100f, BagsCursor.stickScroll(100f, 500f, -dz * 0.9f, 16), 0f)
        val slow = BagsCursor.stickScroll(100f, 500f, 0.5f, 100) - 100f
        val fast = BagsCursor.stickScroll(100f, 500f, 1f, 100) - 100f
        assertTrue(slow > 0f && fast > slow)                                           // down scrolls down, proportional
        assertEquals(BagsCursor.STICK_SPEED * 0.1f, fast, 0.5f)
        assertTrue(BagsCursor.stickScroll(100f, 500f, -1f, 50) < 100f)                  // up scrolls back up
        assertEquals(500f, BagsCursor.stickScroll(490f, 500f, 1f, 1000), 0f)            // clamped to the tooltip's end
        assertEquals(0f, BagsCursor.stickScroll(10f, 500f, -1f, 1000), 0f)              // ... and its top
        assertEquals(0f, BagsCursor.stickScroll(0f, 0f, 1f, 1000), 0f)                  // a tooltip that fits: none
        // the stick is no press: the cursor's copy never moves for it
        val p = BagsCursor.Predictor()
        p.game(true, 1, 0 to 1, open, false, 0)
        assertFalse(p.press("PADRSTICK", 10)); assertNull(p.shown)
    }
}
