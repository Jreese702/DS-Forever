package com.jrees.wowcompanion

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub #43, THE TAP-TO-CAST BAR (the app half; the add-on half is ActionBar.lua cb1-cb3 and Book.lua b53-b56, proven by
 * tools/test_cast_bar.py): the `castbar` document (cb6: 8 slots, the user's "8 total, 4 per side"), the `castcd` cooldowns, the ONE tap per slot, cb3's setting (Off /
 * Left / Right / Both, Off by default, the old Home row's On -> Left), its place on Home per mode, cb5's scroll bar (Left / Right a
 * strip of 8 with 4 in view at Both's size; cb6 Both = 4 under each column at Left's / Right's places, stopping anywhere, a light fling, edge arrows by the scroll; a tap casts the slot under the finger), the Bind box's Cast bar tab (only while the setting is on), and the rules the page keeps (no page text, menus translated).
 */
class CastStripModelTest {
    private fun bar(json: String) = CastStripModel.parse(JSONObject(json))!!
    private val L = CastStripModel.Side.LEFT
    private val R = CastStripModel.Side.RIGHT
    private val BOTH = CastStripModel.Side.BOTH
    private val OFF = CastStripModel.Side.OFF

    private val two = """{"type":"castbar","v":"cb6","n":8,"pick":8,"pp":[39,19],"e":[{"s":1,"id":403,"i":136048,"c":0},
        {"s":3,"id":331,"i":136052,"q":1},{"s":5,"q":1}]}"""

    @After fun down() { HomeSettings.startForTest(null) }

    /** cb9 (the user, 2026-11-13: "in the controller menu we need a Tab for the cast bar if its enabled, where people can assign anything
     *  from the 5 tabs"): every kind read; the Spellbook's reads count spells only; the Controller room's tab and its taps */
    @Test fun cb9ReadsEveryKind() {
        val b = bar("""{"type":"castbar","v":"cb9","n":8,"any":1,"e":[{"s":1,"id":403,"i":136048,"c":0},
            {"s":2,"k":"command","key":"DSMAP","n":"Open Map","i":134269,"c":1},
            {"s":3,"k":"item","id":118,"n":"Minor Healing Potion","i":134829,"ct":7,"cons":1,"c":2},
            {"s":4,"k":"item","id":6948,"n":"Hearthstone","i":134414,"ct":1,"c":3},
            {"s":5,"k":"mount","id":6,"n":"Brown Horse","i":132261,"q":1},{"s":6,"k":"item","id":118,"ct":0,"cons":1,"c":5}]}""")
        assertTrue(b.anyKind)
        assertEquals(CastStripModel.Kind.SPELL, b.slots[0].kind)
        assertEquals(CastStripModel.Kind.COMMAND, b.slots[1].kind)
        assertEquals("DSMAP", b.slots[1].key)
        assertNull("a command has no id", b.slots[1].id)
        assertEquals("Open Map", b.slots[1].name)
        assertEquals(CastStripModel.ROW to 1, CastStripModel.tapCell(b, 1))
        assertEquals(7, b.slots[2].countShown)
        assertNull("a single Hearthstone shows no count", b.slots[3].countShown)
        assertEquals("none left: 0 shown", 0, b.slots[5].countShown)
        assertEquals(CastStripModel.Kind.MOUNT, b.slots[4].kind)
        assertNull("a queued slot takes no tap", CastStripModel.tapCell(b, 4))
        assertTrue(b.has(403))
        assertFalse("an item id is no spell for the Bind box", b.has(118))
        assertEquals(-1, b.slotOf(118, 118))
        assertTrue(b.hasSpells)
        assertFalse("a cb6 bar takes spells only", bar(two).anyKind)
        assertTrue(CastStripModel.ctlTab(b))
        assertTrue("cb14 (D2): the bar Off no longer hides the sub-tabs (dimmed)", CastStripModel.barOff(OFF) && CastStripModel.ctlTab(b))
        assertFalse(CastStripModel.ctlTab(bar(two)))
        assertEquals(listOf("padtab" to 39, "padtab" to 29), CastStripModel.ctlTabCells())
        assertEquals(listOf("padtab" to 39, "padtab" to 30), CastStripModel.ctlSettingCells(true))
        assertEquals(listOf("padtab" to 39, "padtab" to 31), CastStripModel.ctlSettingCells(false))
    }

    @Test fun theDocumentReadsEightSlots() {
        val b = bar(two)
        assertEquals(8, b.slots.size)
        assertEquals(CastStripModel.Slot(0, 403, 136048, 0, false), b.slots[0])
        assertEquals(CastStripModel.Slot(2, 331, 136052, null, true), b.slots[2])
        assertEquals(CastStripModel.Slot(4, null, null, null, true), b.slots[4])        // emptied by a queued edit: dimmed
        assertEquals(CastStripModel.Slot(1, null, null, null, false), b.slots[1])
        assertEquals(8, b.pick)
        assertTrue(b.hasSpells); assertTrue(b.has(403)); assertTrue(b.has(331)); assertFalse(b.has(324)); assertFalse(b.full)
        assertEquals("cb6", b.version)
        assertEquals(2, b.slotOf(0, 331)); assertEquals(0, b.slotOf(403, 77777)); assertEquals(-1, b.slotOf(324, null))
        assertNull(CastStripModel.parse(JSONObject("""{"type":"facecd"}""")))
        // an older add-on's 12: slots past 8 are dropped, never shifted onto another slot
        val old = bar("""{"type":"castbar","v":"cb2b","n":12,"pick":12,"e":[{"s":2,"id":5,"c":1},{"s":9,"id":7,"c":8}]}""")
        assertEquals(listOf(null, 5, null, null, null, null, null, null), old.slots.map { it.id })
        // cb6: slots 7 and 8 are read, their cells 6 and 7
        val eight = bar("""{"type":"castbar","v":"cb6","n":8,"pick":8,"e":[{"s":7,"id":11,"c":6},{"s":8,"id":12,"c":7}]}""")
        assertEquals("castbar" to 6, CastStripModel.tapCell(eight, 6)); assertEquals("castbar" to 7, CastStripModel.tapCell(eight, 7))
    }

    @Test fun anEmptyBarOrNoPadRow() {
        assertFalse(bar("""{"type":"castbar","v":"cb3","n":6,"pick":6,"e":[]}""").hasSpells)
        assertFalse(bar("""{"type":"castbar","v":"cb3","n":6,"pick":6,"e":{}}""").hasSpells)   // the encoder's empty table may be an object
        val noPad = bar("""{"type":"castbar","v":"cb3","n":6,"nopad":1,"pp":[39,19],"e":[{"s":1,"id":403,"q":1}]}""")
        assertNull(noPad.pick)
        assertNull(CastStripModel.tapCell(noPad, 0))
        assertTrue(noPad.pickable)
        val bad = bar("""{"type":"castbar","pick":6,"e":[{"s":0,"id":1,"c":0},{"s":9,"id":2,"c":8},{"s":2,"id":5,"c":40}]}""")
        assertEquals(listOf(null, 5), bad.slots.take(2).map { it.id })
        assertNull(bad.slots[1].cell)
    }

    @Test fun aTapIsOneCellOnTheCastbarRow() {
        val b = bar(two)
        assertEquals("castbar" to 0, CastStripModel.tapCell(b, 0))
        assertNull(CastStripModel.tapCell(b, 1))           // empty
        assertNull(CastStripModel.tapCell(b, 2))           // waiting for the fight's end
        assertNull(CastStripModel.tapCell(b, 4))
        assertNull(CastStripModel.tapCell(b, 6))
        assertNull(CastStripModel.tapCell(b, 8))
        assertNull(CastStripModel.tapCell(null, 0))
    }

    // ---------------------------------------------------------------- cb3: the setting

    @Test fun offByDefaultAndTheOldRowsOnIsLeft() {
        assertEquals(OFF, CastStripModel.sideOf(null, null))                 // never chosen: Off (the user: "off by default")
        assertEquals(L, CastStripModel.sideOf(null, true))                   // the old Home row On -> Left
        assertEquals(OFF, CastStripModel.sideOf(null, false))
        assertEquals(R, CastStripModel.sideOf("RIGHT", true))                // the new choice wins
        assertEquals(BOTH, CastStripModel.sideOf("BOTH", null))
        assertEquals(OFF, CastStripModel.sideOf("NONSENSE", null))
        assertEquals(listOf("Off", "Left", "Right", "Both"), CastStripModel.Side.values().map { it.label })
        assertFalse(CastStripModel.shown(OFF)); assertTrue(CastStripModel.shown(L)); assertTrue(CastStripModel.shown(BOTH))
    }

    @Test fun theSettingMigratesAndSaves() {
        val p = Prefs(hashMapOf("castStrip" to true))
        HomeSettings.startForTest(p)
        assertEquals(L, HomeSettings.castBarSide)                             // old row on: Left
        HomeSettings.castBarSide = R
        assertEquals("RIGHT", p.m["castBarSide"]); assertFalse(p.m.containsKey("castStrip"))
        assertEquals(R, HomeSettings.castBarSide)
        HomeSettings.startForTest(Prefs(hashMapOf("castStrip" to false)))
        assertEquals(OFF, HomeSettings.castBarSide)
        HomeSettings.startForTest(Prefs())
        assertEquals(OFF, HomeSettings.castBarSide)
    }

    // ---------------------------------------------------------------- cb3: the place per mode (a Thor bottom screen, 1080 x 1240)

    private val W = 1080f
    private val H = 1240f
    private val G = UiKit.MODULE_GAP

    @Test fun theLayoutPerMode() {
        assertNull(CastStripModel.place(OFF, W, H, G))
        assertEquals(0f, CastStripModel.height(OFF, W, G), 0f)
        val l = CastStripModel.place(L, W, H, G)!!
        val r = CastStripModel.place(R, W, H, G)!!
        val b = CastStripModel.place(BOTH, W, H, G)!!
        // Left under the chat (the left column), Right under the minimap (the right column), Both under both; each at the page's foot
        assertEquals(G, l.l, 0f); assertEquals(W / 2f - 4f, l.r, 0f)
        assertEquals(W / 2f + 4f, r.l, 0f); assertEquals(W - G, r.r, 0f)
        assertEquals(G, b.l, 0f); assertEquals(W - G, b.r, 0f)
        for (x in listOf(l, r, b)) assertEquals(H - G, x.b, 0f)
        assertEquals(l.h, r.h, 0f)
        assertTrue(CastStripModel.cutsLeft(L) && !CastStripModel.cutsRight(L))
        assertTrue(CastStripModel.cutsRight(R) && !CastStripModel.cutsLeft(R))
        assertTrue(CastStripModel.cutsLeft(BOTH) && CastStripModel.cutsRight(BOTH))
        // cb5 / cb6: Left / Right a strip of all 8 with 4 in view, Both all 8 (4 a column); every mode's slots ONE size (the user: "the same size as the both option")
        assertEquals(4, CastStripModel.visible(L)); assertEquals(4, CastStripModel.visible(R)); assertEquals(8, CastStripModel.visible(BOTH))
        assertEquals(0, CastStripModel.visible(OFF))
        assertTrue(CastStripModel.scrolls(L) && CastStripModel.scrolls(R)); assertFalse(CastStripModel.scrolls(BOTH) || CastStripModel.scrolls(OFF))
        assertEquals(l.h, b.h, 0.01f)                                         // no dots band any more: one height
        val sl = CastStripModel.slotBox(L, 0, l.w, l.h)
        val sr = CastStripModel.slotBox(R, 0, r.w, r.h)
        val sb = CastStripModel.slotBox(BOTH, 0, b.w, b.h)
        assertEquals(CastStripModel.MAX_SIDE, sb.w, 0.01f)
        assertEquals(sb.w, sl.w, 0.01f); assertEquals(sb.w, sr.w, 0.01f); assertEquals(sb.h, sl.h, 0.01f)
        assertEquals(CastStripModel.PAD, sl.t, 0.01f)
        for (side in listOf(L, R)) {
            val box = if (side == L) l else r
            val n = CastStripModel.visible(side)
            val slots = (0 until n).map { CastStripModel.slotBox(side, it, box.w, box.h) }
            for (s in slots) { assertEquals(slots[0].t, s.t, 0f); assertTrue(s.l >= 0f && s.r <= box.w && s.t >= 0f && s.b <= box.h) }
            for (i in 1 until n) assertTrue("$side $i no overlap", slots[i].l >= slots[i - 1].r)
            // the slots in view spread evenly (the same room at each end as half the room between two): Both's look, 4 on Left / Right
            val between = slots[1].l - slots[0].r
            assertEquals("$side", between, (slots[0].l - CastStripModel.PAD) * 2f, 0.01f)
            assertEquals("$side", between, (box.w - CastStripModel.PAD - slots[n - 1].r) * 2f, 0.01f)
            for (i in 2 until n) assertEquals("$side even", between, slots[i].l - slots[i - 1].r, 0.01f)
        }
        // the 5th to 8th wait off the right edge, beyond the window
        assertTrue(CastStripModel.slotBox(L, 4, l.w, l.h).l >= CastStripModel.window(l.w).second)
        assertEquals(listOf(0, 1, 2, 3), CastStripModel.inView(L, l.w, l.h, 0f))
        assertEquals((0 until 8).toList(), CastStripModel.inView(BOTH, b.w, b.h, 0f))
    }

    /** cb8 (the user: "I want on both the UI is split down the middle. I want it to be one full bar"): Both = ONE bar across the
     *  whole width (no split), all 8 at the one size spread evenly across it, the columns above ending on the one bar's top; no scroll */
    @Test fun bothIsOneFullBarOfEight() {
        val l = CastStripModel.place(L, W, H, G)!!
        val b = CastStripModel.place(BOTH, W, H, G)!!
        assertEquals(G, b.l, 0f); assertEquals(W - G, b.r, 0f)
        assertEquals(l.t, b.t, 0.01f)                                         // one top: both columns end on it
        val slots = (0 until 8).map { CastStripModel.slotBox(BOTH, it, b.w, b.h) }
        for (s in slots) { assertEquals(CastStripModel.MAX_SIDE, s.w, 0.01f); assertEquals(slots[0].t, s.t, 0f) }
        val between = slots[1].l - slots[0].r
        assertTrue(between > 0f)
        for (i in 1 until 8) assertEquals("even $i", between, slots[i].l - slots[i - 1].r, 0.01f)
        assertEquals(between, (slots[0].l - CastStripModel.PAD) * 2f, 0.01f)
        assertEquals(between, (b.w - CastStripModel.PAD - slots[7].r) * 2f, 0.01f)
        // no gap at the middle: the page's middle falls on the even spacing between slots 4 and 5 (and a finger there is in the bar)
        assertEquals(b.w / 2f, (slots[3].r + slots[4].l) / 2f, 0.01f)
        for (i in 0 until 8) {
            val s = slots[i]
            assertEquals(i, CastStripModel.slotAt(BOTH, (s.l + s.r) / 2f, (s.t + s.b) / 2f, b.w, b.h, 0f))
        }
        assertEquals(0f, CastStripModel.maxScroll(BOTH, b.w), 0f)
        assertEquals(CastStripModel.slotBox(BOTH, 5, b.w, b.h), CastStripModel.slotBox(BOTH, 5, b.w, b.h, 300f))   // no scroll
        assertEquals(0f to 0f, CastStripModel.arrows(BOTH, b.w, 0f))
        assertFalse(src("CastStripModel.kt").contains("fun wells("))         // cb6's two wells are gone
    }

    /** cb8 (the user: "on Right, I want it to show the right 4 abilities and scroll left at its resting position"): Right rests at the
     *  strip's end (5-8 in view, the left arrow showing), Left at its start; the kept scroll is per side, a side change starts at its rest */
    @Test fun rightRestsOnTheRightFourAndLeftOnTheFirst() {
        val r = CastStripModel.place(R, W, H, G)!!
        assertEquals(4f, CastStripModel.rest(R), 0f); assertEquals(0f, CastStripModel.rest(L), 0f); assertEquals(0f, CastStripModel.rest(BOTH), 0f)
        val o = CastStripModel.fromPos(R, r.w, CastStripModel.rest(R))
        assertEquals(CastStripModel.maxScroll(R, r.w), o, 0.01f)
        assertEquals(listOf(4, 5, 6, 7), CastStripModel.inView(R, r.w, r.h, o))
        assertEquals(1f to 0f, CastStripModel.arrows(R, r.w, o))              // more that way: left
        val live = CastStripModel.Live
        val (s0, p0) = live.side to live.pos
        try {
            live.side = null; live.pos = 0f
            live.laid(R); assertEquals(4f, live.posFor(R), 0f)
            live.keep(R, 1.5f); live.laid(R); assertEquals(1.5f, live.posFor(R), 0f)      // kept in the run
            live.laid(BOTH); assertEquals(1.5f, live.posFor(R), 0f)                        // Both (the ask picture) does not touch it
            assertEquals(0f, live.posFor(L), 0f)                                           // another side reads its rest
            live.laid(L); assertEquals(0f, live.posFor(L), 0f)
            live.keep(L, 2f); live.laid(R); assertEquals(4f, live.posFor(R), 0f)            // a side change: that side's rest
            live.laid(L); assertEquals(0f, live.posFor(L), 0f)
        } finally { live.side = s0; live.pos = p0 }
    }

    // ---------------------------------------------------------------- cb5: the scroll bar (any place, no pages), its arrows, the tap

    private val bw = 608f
    private val bh = CastStripModel.MAX_SIDE + 2 * CastStripModel.PAD

    @Test fun theStripScrollsToAnyPlaceAndHoldsAtItsEnds() {
        val p = CastStripModel.pitch(L, bw)
        assertEquals((bw - 2 * CastStripModel.PAD) / 4f, p, 0.001f)
        assertEquals(4 * p, CastStripModel.maxScroll(L, bw), 0.001f)          // four slots hidden at the start (cb6: 8 in all)
        assertEquals(0f, CastStripModel.maxScroll(BOTH, 1224f), 0f)           // Both does not scroll
        assertEquals(0f, CastStripModel.maxScroll(L, 0f), 0f)                 // not laid out yet: no scroll, no throw
        assertEquals(0f, CastStripModel.clampScroll(L, bw, -50f), 0f)
        assertEquals(4 * p, CastStripModel.clampScroll(L, bw, 9999f), 0.001f)
        // an arbitrary place between slots stays where it is (no snapping): every slot moves by exactly the scroll
        val o = 0.37f * p + 11f
        assertEquals(o, CastStripModel.clampScroll(L, bw, o), 0f)
        for (i in 0 until 8)
            assertEquals(CastStripModel.slotBox(L, i, bw, bh).l - o, CastStripModel.slotBox(L, i, bw, bh, o).l, 0.001f)
        assertEquals(listOf(0, 1, 2, 3, 4), CastStripModel.inView(L, bw, bh, o))      // part of slot 5 has come in
        // the end: slots 5-8 in view spread evenly, as slots 1-4 were at the start
        val e = CastStripModel.maxScroll(L, bw)
        assertEquals(listOf(4, 5, 6, 7), CastStripModel.inView(L, bw, bh, e))
        assertEquals(CastStripModel.slotBox(L, 0, bw, bh).l, CastStripModel.slotBox(L, 4, bw, bh, e).l, 0.001f)
        assertEquals(CastStripModel.slotBox(L, 3, bw, bh).r, CastStripModel.slotBox(L, 7, bw, bh, e).r, 0.001f)
        // the drag follows the finger (left brings the right-hand slots in), held to the ends
        assertEquals(40f, CastStripModel.dragScroll(L, bw, 0f, -40f), 0f)
        assertEquals(0f, CastStripModel.dragScroll(L, bw, 10f, 300f), 0f)
        assertEquals(e, CastStripModel.dragScroll(L, bw, 10f, -2000f), 0.001f)
        assertEquals(0f, CastStripModel.dragScroll(BOTH, 1224f, 0f, -200f), 0f)
        // the scroll kept for the app's run as slots' shares: the same place after a new layout
        assertEquals(1.25f, CastStripModel.toPos(L, bw, 1.25f * p), 0.0001f)
        assertEquals(1.25f * CastStripModel.pitch(R, 600f), CastStripModel.fromPos(R, 600f, 1.25f), 0.001f)
        assertEquals(4f, CastStripModel.toPos(L, bw, 1e6f), 0.0001f)
    }

    @Test fun theArrowsShowWhereSlotsAreHidden() {
        val p = CastStripModel.pitch(L, bw); val e = CastStripModel.maxScroll(L, bw)
        assertEquals(0f to 1f, CastStripModel.arrows(L, bw, 0f))              // the start: more to the right only
        assertEquals(1f to 0f, CastStripModel.arrows(L, bw, e))               // the end: more to the left only
        assertEquals(1f to 1f, CastStripModel.arrows(L, bw, p))               // between: both ways
        val (fl, fr) = CastStripModel.arrows(R, bw, p * CastStripModel.ARROW_FADE / 2f)
        assertEquals(0.5f, fl, 0.001f); assertEquals(1f, fr, 0f)              // fading in with the scroll
        assertEquals(0f to 0f, CastStripModel.arrows(BOTH, 1224f, 0f))        // Both: no arrows
        // each arrow in the room beside the end slot in view, inside the window, on the slots' middle
        val a = CastStripModel.arrowBox(L, true, bw, bh); val z = CastStripModel.arrowBox(L, false, bw, bh)
        val s0 = CastStripModel.slotBox(L, 0, bw, bh); val s3 = CastStripModel.slotBox(L, 3, bw, bh)
        assertTrue(a.l >= CastStripModel.window(bw).first && a.r <= s0.l)
        assertTrue(z.r <= CastStripModel.window(bw).second && z.l >= s3.r)
        assertEquals((s0.t + s0.b) / 2f, (a.t + a.b) / 2f, 0.01f)
    }

    @Test fun aTapCastsTheSlotUnderTheFingerAtAnyScroll() {
        val p = CastStripModel.pitch(L, bw)
        fun centre(i: Int, o: Float) = CastStripModel.slotBox(L, i, bw, bh, o).let { (it.l + it.r) / 2f to (it.t + it.b) / 2f }
        for (o in listOf(0f, 0.37f * p, p, 1.61f * p, 2 * p, 3.3f * p, 4 * p)) for (i in CastStripModel.inView(L, bw, bh, o)) {
            val (x, y) = centre(i, o)
            if (x < CastStripModel.window(bw).first || x > CastStripModel.window(bw).second) continue
            assertEquals("scroll $o slot $i", i, CastStripModel.slotAt(L, x, y, bw, bh, o))
        }
        // at the end, the leftmost place in view is slot 5, the rightmost slot 8
        val e = CastStripModel.maxScroll(L, bw)
        assertEquals(4, CastStripModel.slotAt(L, 40f, bh / 2f, bw, bh, e))
        assertEquals(7, CastStripModel.slotAt(L, bw - 40f, bh / 2f, bw, bh, e))
        assertEquals(-1, CastStripModel.slotAt(L, 2f, bh / 2f, bw, bh, 0f))           // outside the window: nothing
        assertEquals(-1, CastStripModel.slotAt(L, 100f, 0.5f, bw, bh, 0f))            // above the slots' row: nothing
        assertEquals(-1, CastStripModel.slotAt(OFF, 100f, bh / 2f, bw, bh, 0f))
        val b = bar("""{"type":"castbar","v":"cb6","n":8,"pick":8,"pp":[39,19],"e":[{"s":1,"id":403,"i":136048,"c":0},
            {"s":4,"id":8042,"i":136026,"c":3},{"s":8,"id":2484,"i":136102,"c":7}]}""")
        assertEquals("castbar" to 7, CastStripModel.tapCell(b, CastStripModel.slotAt(L, bw - 40f, bh / 2f, bw, bh, e)))
        // a finger that wobbles is still a tap; one that moved more than 24 px is a drag (never a cast)
        assertFalse(CastStripModel.leftTap(6f, 3f)); assertFalse(CastStripModel.leftTap(24f, 0f))
        assertTrue(CastStripModel.leftTap(25f, 0f)); assertTrue(CastStripModel.leftTap(18f, 18f))
    }

    @Test fun aFlingGlidesWithFrictionAndStopsAnywhere() {
        // the finger's speed over its last 100 ms
        assertEquals(0f, CastStripModel.velocity(listOf(0L), listOf(5f)), 0f)
        assertEquals(-2f, CastStripModel.velocity(listOf(0L, 300L, 350L, 400L), listOf(0f, 0f, -100f, -200f)), 0.001f)
        assertEquals(1f, CastStripModel.velocity(listOf(0L, 10L, 20L), listOf(0f, 10f, 20f)), 0.001f)
        assertFalse(CastStripModel.flings(0.1f)); assertTrue(CastStripModel.flings(-1.5f))
        // a leftward flick: the strip goes on rightward-scrolling, slowing; it never passes the end
        val v = -1.0f
        val a = CastStripModel.flingAt(L, bw, 50f, v, 50f); val b2 = CastStripModel.flingAt(L, bw, 50f, v, 100f)
        val c = CastStripModel.flingAt(L, bw, 50f, v, 150f)
        assertTrue(a > 50f && b2 > a && c > b2); assertTrue(b2 - a > c - b2)  // friction: each step shorter
        assertEquals(50f, CastStripModel.flingAt(L, bw, 50f, v, 0f), 0f)
        // its rest is NOT on a slot's place: light momentum (0.7 x 1 px/ms x 260 ms = 182 px), no snap
        val rest = CastStripModel.flingAt(L, bw, 50f, v, 5000f)
        assertEquals(50f + 182f, rest, 0.5f)
        val p = CastStripModel.pitch(L, bw)
        assertTrue((rest / p) % 1f in 0.05f..0.95f)
        assertTrue(CastStripModel.flingDone(L, bw, 50f, v, 5000f)); assertFalse(CastStripModel.flingDone(L, bw, 50f, v, 50f))
        // a hard flick stops at the end
        val e = CastStripModel.maxScroll(L, bw)
        assertEquals(e, CastStripModel.flingAt(L, bw, 50f, -10f, 5000f), 0.001f)
        assertTrue(CastStripModel.flingDone(L, bw, 50f, -10f, 400f))
        assertEquals(0f, CastStripModel.flingAt(L, bw, 50f, 10f, 5000f), 0f)
        assertEquals(0f, CastStripModel.flingAt(BOTH, 1224f, 0f, -3f, 300f), 0f)       // Both does not move
    }

    /** the scroll never moves the bar: the side is set in Settings only; cb5: no pages are left (no flip, no dots) */
    @Test fun theScrollNeverChangesTheSideAndThePagesAreGone() {
        val p = Prefs(hashMapOf("castBarSide" to "LEFT"))
        HomeSettings.startForTest(p)
        assertEquals(L, HomeSettings.castBarSide); assertEquals("LEFT", p.m["castBarSide"])
        val m = src("CastStripModel.kt")
        for (gone in listOf("fun swipeTo(", "fun flipTo(", "fun dots(", "DOTS_H", "fun perPage(", "var page")) assertFalse(gone, m.contains(gone))
        val home = src("HomeView.kt")
        assertFalse(home.contains("castBarSide ="))                           // Home only reads the setting
        assertFalse(home.contains("castStripSwipe"))
        val v = src("CastStripView.kt")
        assertFalse(v.contains("CastStripModel.Side) -> Unit")); assertFalse(v.contains("drawCircle"))
        assertTrue(v.contains("Icons.get(it) ?: UiKit.icon(it)"))
        // the Bind box's Cast bar tab shows the slots' spells from icons.pak too (the kit alone has no spell icons)
        assertTrue(src("SpellsOverlay.kt").contains("?.let { Icons.get(it) ?: UiKit.icon(it) }"))      // cb13: a macro slot with no picture: the question mark
    }

    // ---------------------------------------------------------------- cb3: the Bind box's Cast bar tab (Book b56)

    @Test fun theBindTabWhileTheAddonTakesASlotPick() {
        val b = bar(two)
        assertTrue(b.slotPick)
        // cb14 (D2): the setting Off no longer hides the tab (dimmed with Turn on); the add-on's slot pick decides
        assertTrue(CastStripModel.bindTab(b))
        assertFalse(CastStripModel.bindTab(null))                            // no document yet
        val older = bar("""{"type":"castbar","v":"cb2b","n":12,"pick":12,"pp":[39,19],"e":[]}""")
        assertFalse(older.slotPick); assertFalse(CastStripModel.bindTab(older))     // an older add-on takes no slot pick
        val six = bar("""{"type":"castbar","v":"cb3","n":6,"pick":6,"pp":[39,19],"e":[]}""")
        assertFalse(six.slotPick)                                             // cb3's 6: its Book knows no 27-28 (cb6 only)
        assertEquals(listOf("padtab" to 39, "padtab" to 26), CastStripModel.tabCells())
        assertEquals(listOf("padtab" to 39, "padtab" to 20), CastStripModel.slotCells(0))
        assertEquals(listOf("padtab" to 39, "padtab" to 25), CastStripModel.slotCells(5))
        // cb6 / Book b57: slots 7-8 are 27-28 (26 stays the tab)
        assertEquals(listOf("padtab" to 39, "padtab" to 27), CastStripModel.slotCells(6))
        assertEquals(listOf("padtab" to 39, "padtab" to 28), CastStripModel.slotCells(7))
        assertNull(CastStripModel.slotCells(8)); assertNull(CastStripModel.slotCells(-1))
        val codes = (0 until 8).map { CastStripModel.slotCode(it) }
        assertEquals(listOf(20, 21, 22, 23, 24, 25, 27, 28), codes)
        // no collision with the pick's other codes: 0 pet recall, 1-4 cb14's (the retired Smart Range codes), 5-18 the radial ops, 19
        // the pick, 26 the tab
        for (k in codes) assertFalse("$k", k in 0..19 || k == CastStripModel.TAB_CODE || k == CastStripModel.PICK)
    }

    @Test fun theBindTabReadsTheAddonsCursor() {
        val c = SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b56","ctl":{"on":true,"mode":"bind","bind":403,"bcb":3}}"""))
        assertEquals(3, c?.bindCb)
        val eighth = SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b57","ctl":{"on":true,"mode":"bind","bind":403,"bcb":8}}"""))
        assertEquals(8, eighth?.bindCb)
        val none = SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b56","ctl":{"on":true,"mode":"bind","bind":403,"bcb":9}}"""))
        assertNull(none?.bindCb)
    }

    @Test fun theCooldowns() {
        val m = CastStripModel.parseCd(JSONObject("""{"type":"castcd","v":"cb3","k":{"1":{"d":1.5,"l":1.5,"g":true},
            "3":{"d":8,"l":6,"e":true},"9":{"d":4,"l":2},"4":{"d":4,"l":9},"8":{"d":4,"l":2}}}"""))!!
        assertEquals(setOf(0, 2, 7), m.keys)
        assertTrue(m[0]!!.gcd); assertFalse(m[0]!!.estimated)
        assertTrue(m[2]!!.estimated)
        assertEquals(emptyMap<Int, PadMapModel.FaceCd>(), CastStripModel.parseCd(JSONObject("""{"type":"castcd","v":"cb3"}""")))
        assertNull(CastStripModel.parseCd(JSONObject("""{"type":"facecd"}""")))
        val cd = m[2]!!
        assertEquals(0.75f, CastStripModel.sweepLeft(cd, 10_000L, 10_000L), 0.001f)
        assertEquals(0.5f, CastStripModel.sweepLeft(cd, 10_000L, 12_000L), 0.001f)
        assertEquals(0f, CastStripModel.sweepLeft(cd, 10_000L, 20_000L), 0.001f)
    }

    @Test fun theLiveBarTellsItsListeners() {
        var n = 0
        CastStripModel.Live.onChange { n++ }
        val b = bar(two)
        CastStripModel.Live.set(b)
        CastStripModel.Live.set(b)                         // the same bar again: nothing new
        assertEquals(1, n)
        assertEquals(b, CastStripModel.Live.bar)
    }

    private fun src(name: String): String {
        val dir = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
            .map { java.io.File(it) }.first { it.isDirectory }
        return java.io.File(dir, name).readText()
    }

    /** terse on-screen text: the strip writes nothing at all (icons and the ring only) */
    @Test fun theStripHasNoPageText() {
        val v = src("CastStripView.kt")
        assertFalse(v.contains("drawText")); assertFalse(v.contains("Tr.t(")); assertFalse(v.contains("StaticLayout"))
        assertTrue(v.contains("UiKit.ringBox"))                                // the kit's HUD ring edge on every slot
    }

    /** cb13 (GitHub #553, a player: "I cannot assign macros to the bottom screen cast bar"): a player's macro on a slot, by name; a
     *  gone macro (`miss`) takes no tap; the Macros room's Bind box gets the Cast bar tab only while the add-on takes macros (`mac`) */
    @Test fun cb13ReadsAMacroSlot() {
        val b = bar("""{"type":"castbar","v":"cb13","n":8,"any":1,"mac":1,"pp":[39,19],"pick":8,"e":[
            {"s":1,"k":"macro","n":"Bravo","i":134400,"c":0},{"s":2,"k":"macro","n":"Gone","miss":1,"q":1,"c":1},
            {"s":3,"k":"macro","n":"Heal","i":136243,"c":2,"id":77},{"s":4,"k":"macro","c":3}]}""")
        assertTrue(b.macros)
        val s = b.slots[0]
        assertEquals(CastStripModel.Kind.MACRO, s.kind)
        assertEquals("Bravo", s.name)
        assertNull("a macro has no id (it goes by name)", s.id)
        assertNull("nor a command key", s.key)
        assertTrue(s.filled)
        assertEquals(134400, s.icon)
        assertNull("no count on a macro", s.countShown)
        assertEquals(CastStripModel.ROW to 0, CastStripModel.tapCell(b, 0))
        assertTrue("a gone macro keeps its name", b.slots[1].missing && b.slots[1].filled)
        assertNull("a gone macro has no cell, takes no tap", b.slots[1].cell)
        assertNull(CastStripModel.tapCell(b, 1))
        assertNull("a stray id on a macro is ignored", b.slots[2].id)
        assertFalse("a macro is no spell for the Spellbook's reads", b.has(77))
        assertFalse("a macro row with no name is empty", b.slots[3].filled)
        assertNull(CastStripModel.tapCell(b, 3))
        assertTrue(b.hasSpells)
        // the Bind box's tab: a spell's box as before; a macro's only while the add-on says mac = 1
        assertTrue(CastStripModel.bindTab(b, macro = true))
        val cb12 = bar("""{"type":"castbar","v":"cb12","n":8,"any":1,"pp":[39,19],"e":[]}""")
        assertFalse(cb12.macros)
        assertTrue("a spell's box on a cb12 add-on: unchanged", CastStripModel.bindTab(cb12))
        assertFalse("a macro's box on a cb12 add-on: no Cast bar tab", CastStripModel.bindTab(cb12, macro = true))
        // the overlay asks with the box's macro flag; cb14 (D8): a bag item's box takes the Tap to cast tabs too
        val o = src("SpellsOverlay.kt")
        assertFalse(o.contains("if (bi != null && !bi.mac) return false"))
        assertTrue(o.contains("CastStripModel.bindTab(CastStripModel.Live.bar, macro = bi?.mac == true)"))
    }

    /** cb3: the Bind box's third tab replaces the Spellbook's own "Cast bar" button; the Settings row is Off / Left / Right / Both */
    @Test fun theThirdTabReplacesTheSpellbooksButton() {
        assertFalse(src("SpellbookView.kt").contains("\"Cast bar\""))
        val o = src("SpellsOverlay.kt")
        // cb15 / b71 (Option C): the box's ONE row of situations, both devices in its body
        assertTrue(o.contains("val sitNames = listOf(\"Out of combat\", \"In combat\", \"Party mode\")"))
        assertTrue(o.contains("darkSection(c, \"Controller\"") && o.contains("darkSection(c, \"Tap-to-cast bar\""))
        assertFalse(o.contains("\"Normal\""))
        val h = src("HomeSettings.kt")
        assertTrue(h.contains("row(c, y + GAP, \"Tap-to-cast bar\""))
        assertFalse(h.contains("castStrip ="))
    }

    @Test fun theMenuWordsAreInSpanishAndFrench() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { java.io.File(it) }.first { it.isDirectory }
        for (l in listOf("es", "fr")) {
            Tr.load(l, java.io.File(dir, "$l.json").readText(Charsets.UTF_8))
            for (w in listOf("Tap-to-cast bar", "Eight spells, one tap each.", "Eight spells in one bar under both;", "swipe left for more.", "Cast bar", "Off", "Left", "Right", "Both", "Place",
                "Four spells under the chat;", "Four spells under the minimap;", "swipe for more."))
                assertTrue("$l: $w", Tr.t(w, l) != w)
        }
    }

    /** SharedPreferences over a map (android.jar's interface; no device) */
    private class Prefs(val m: HashMap<String, Any?> = HashMap()) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = m
        override fun getString(k: String?, d: String?) = m[k] as? String ?: d
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(k: String?, d: MutableSet<String>?) = m[k] as? MutableSet<String> ?: d
        override fun getInt(k: String?, d: Int) = m[k] as? Int ?: d
        override fun getLong(k: String?, d: Long) = m[k] as? Long ?: d
        override fun getFloat(k: String?, d: Float) = m[k] as? Float ?: d
        override fun getBoolean(k: String?, d: Boolean) = m[k] as? Boolean ?: d
        override fun contains(k: String?) = m.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val put = HashMap<String, Any?>(); val gone = HashSet<String>()
            override fun putString(k: String, v: String?) = apply { put[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = apply { put[k] = v }
            override fun putInt(k: String, v: Int) = apply { put[k] = v }
            override fun putLong(k: String, v: Long) = apply { put[k] = v }
            override fun putFloat(k: String, v: Float) = apply { put[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { put[k] = v }
            override fun remove(k: String) = apply { gone.add(k) }
            override fun clear() = apply { gone.addAll(m.keys) }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { for (k in gone) m.remove(k); m.putAll(put) }
        }
    }

    // ---------------------------------------------------------------- cb14: CONTROLLER | TAP TO CAST (the user, 2026-11-19)

    private val fightDoc = """{"type":"castbar","v":"cb14","n":8,"any":1,"mac":1,"fl":1,"show":1,"pick":8,"pp":[39,19],
        "e":[{"s":1,"id":403,"c":0},{"s":3,"id":331,"c":2}],
        "f":[{"s":1,"id":324,"c":9},{"s":2,"k":"command","key":"TARGETNEARESTENEMY","n":"Target","c":10},{"s":4,"id":5,"q":1},{"s":5,"id":6,"c":3}]}"""

    @Test fun theFightLayerIsParsed() {
        val b = bar(fightDoc)
        assertTrue(b.fightLayer && b.show)
        assertEquals(324, b.fslots[0].id); assertEquals(9, b.fslots[0].cell)
        assertEquals(CastStripModel.Kind.COMMAND, b.fslots[1].kind); assertEquals(10, b.fslots[1].cell)
        assertFalse("an unset fight slot", b.fslots[2].filled)
        assertTrue(b.fslots[3].queued); assertNull(b.fslots[3].cell)
        assertNull("a fight cell must be 9-16 (3 is a slot's)", b.fslots[4].cell)
        assertNull("a slot's cell must be 0-7", bar(fightDoc.replace("\"c\":2}", "\"c\":12}")).slots[2].cell)
        assertFalse("an older add-on: no layer, not shown", bar(two).fightLayer || bar(two).show)
    }

    @Test fun aFightTapsTheOverrideCellOnlyWithShowOn() {
        val b = bar(fightDoc)
        assertEquals("out of combat: slot 1's own", CastStripModel.ROW to 0, CastStripModel.tapCell(b, 0))
        assertEquals("in a fight: its fight cell", CastStripModel.ROW to 9, CastStripModel.tapCell(b, 0, fight = true))
        assertEquals("an empty fight slot keeps the slot's own in a fight", CastStripModel.ROW to 2, CastStripModel.tapCell(b, 2, fight = true))
        assertEquals("a fight slot over an empty slot: in fights only", CastStripModel.ROW to 10, CastStripModel.tapCell(b, 1, fight = true))
        assertNull(CastStripModel.tapCell(b, 1))
        assertNull("a queued fight slot takes no tap", CastStripModel.tapCell(b, 3, fight = true))
        assertEquals(324, CastStripModel.shownSlot(b, 0, fight = true)?.id)
        assertEquals(403, CastStripModel.shownSlot(b, 0)?.id)
        val off = bar(fightDoc.replace("\"show\":1,", ""))
        assertEquals("Show off: the slot's own in a fight", CastStripModel.ROW to 0, CastStripModel.tapCell(off, 0, fight = true))
        assertEquals(403, CastStripModel.shownSlot(off, 0, fight = true)?.id)
    }

    /**
     * the 11-22 review: In fights = Top screen keeps the bar in the fight whatever Show in combat says (TopCombatBar.stripCombat: STAY),
     * but it shows the layer Show in combat arms - with Show off (the default) the add-on builds no fight cells (`show` absent), so the
     * kept bar shows and casts each slot's OWN entry (never a fight slot it cannot cast); with Show on, the In-combat layer as ever
     */
    @Test fun aTopScreenFightKeepsTheBarOnTheLayerShowArms() {
        val top = HomeLayout(fight = HomeLayout.FightBand.TOP, cast = HomeLayout.CastDock.UNDER_CHAT)
        val mode = TopCombatBar.stripCombat(top, setting = CastStripModel.Combat.HIDE)
        assertFalse("kept: not hidden in the Top fight", CastStripModel.hiddenIn(mode, fighting = true))
        val fightNow = CastStripModel.showOf(mode)                            // CastStripView.fight's fightNow in the fight
        val off = bar(fightDoc.replace("\"show\":1,", ""))
        assertFalse(off.show)
        assertEquals("Show off: slot 1's own spell shown", 403, CastStripModel.shownSlot(off, 0, fight = fightNow)?.id)
        assertEquals("Show off: slot 1's own cell cast", CastStripModel.ROW to 0, CastStripModel.tapCell(off, 0, fight = fightNow))
        assertNull("Show off: a fight slot over an empty slot is not offered", CastStripModel.tapCell(off, 1, fight = fightNow))
        assertNull("Show off: no In-combat badge", CastStripModel.shownLayer(off, 0, fight = fightNow))
        val on = bar(fightDoc)
        assertEquals("Show on: the In-combat layer", 324, CastStripModel.shownSlot(on, 0, fight = fightNow)?.id)
        assertEquals(CastStripModel.ROW to 9, CastStripModel.tapCell(on, 0, fight = fightNow))
        assertEquals("f", CastStripModel.shownLayer(on, 0, fight = fightNow))
    }

    @Test fun showInCombatIsCastBarCombat() {
        assertTrue(CastStripModel.showOf(CastStripModel.Combat.STAY))
        assertFalse(CastStripModel.showOf(CastStripModel.Combat.HIDE))
        assertEquals(CastStripModel.Combat.STAY, CastStripModel.combatFor(true))
        assertEquals(CastStripModel.Combat.HIDE, CastStripModel.combatFor(false))
        assertEquals("D5b: the row's words", listOf("Off", "On"), CastStripModel.Combat.values().map { it.label })
        assertEquals("the default stays Hide (off)", CastStripModel.Combat.HIDE, CastStripModel.combatOf(null))
        assertEquals(listOf("padtab" to 39, "padtab" to 2), CastStripModel.showCells(true))
        assertEquals(listOf("padtab" to 39, "padtab" to 3), CastStripModel.showCells(false))
        assertEquals(listOf("padtab" to 39, "padtab" to 1), CastStripModel.ctlFightCells())
        assertEquals(CastStripModel.Side.BOTH, CastStripModel.turnOnSide)
        assertTrue(CastStripModel.barOff(OFF)); assertFalse(CastStripModel.barOff(L))
    }

    @Test fun theBindBoxWalksFourTabs() {
        val t = CastStripModel.BIND_TABS
        assertEquals(listOf(0, 1, 2, 3), t.take(4).map { it.index })              // (cb15: Party mode's two after them)
        var at = t[0]
        val seen = arrayListOf(at)
        repeat(4) { at = CastStripModel.stepBind(at, 1, tap = true); seen += at }
        assertEquals("RB: Out·Controller, Out·Tap, In·Controller, In·Tap, then it stops", listOf(t[0], t[1], t[2], t[3], t[3]), seen)
        assertEquals(t[2], CastStripModel.stepBind(t[3], -1, tap = true))
        assertEquals(t[0], CastStripModel.stepBind(t[0], -1, tap = true))
        assertEquals("no Tap to cast: two tabs", t[2], CastStripModel.stepBind(t[0], 1, tap = false))
        assertEquals("an add-on without the in-combat layer: three", t[2], CastStripModel.stepBind(t[2], 1, tap = true, fightTap = false))
        assertEquals(listOf("spells" to 11), CastStripModel.bindTabCells(t[0], 10 to 11))
        assertEquals(listOf("spells" to 10), CastStripModel.bindTabCells(t[2], 10 to 11))
        assertEquals(listOf("padtab" to 39, "padtab" to 26), CastStripModel.bindTabCells(t[1], 10 to 11))
        assertEquals(listOf("padtab" to 39, "padtab" to 4), CastStripModel.bindTabCells(t[3], null))
        assertNull(CastStripModel.bindTabCells(t[0], null))
        val c = SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b70","ctl":{"on":true,"mode":"bind","bind":403,"bcb":2,"bcf":1,"cbon":3}}"""))
        assertEquals(2, c?.bindCb); assertTrue(c!!.bindCbFight); assertEquals(3, c.cbOn)
        assertTrue("D8: a bag item's box too", CastStripModel.bindTab(bar(fightDoc)))
        assertTrue(CastStripModel.bindFightTab(bar(fightDoc)))
        assertFalse(CastStripModel.bindFightTab(bar(two)))
    }

    @Test fun theRoomsDocumentCarriesTheSubTabs() {
        val c = PadMapModel.parseCtl(JSONObject("""{"type":"padtabctl","v":"p55","c":{"on":true,"focus":"subtabs","view":"cbfight","top":"combat",
            "tcur":3,"cbsn":2,"cbon":1,"cbs":1}}"""))!!
        assertEquals("cbfight", c.view); assertTrue(c.cbFight); assertEquals("combat", c.top)
        assertEquals(2, c.cbsn); assertEquals(1, c.cbon); assertTrue(c.cbShow); assertFalse(c.cbOff)
        assertEquals("normal", PadMapModel.topOf("castbar")); assertEquals("combat", PadMapModel.topOf("cbfight"))
        assertEquals(7, PadMapModel.tabOf("cbfight"))
        val old = PadMapModel.parseCtl(JSONObject("""{"type":"padtabctl","v":"p54","c":{"on":true,"view":"castbar"}}"""))!!
        assertEquals("an older add-on: the top from the view", "normal", old.top)
        assertNull(old.cbsn)
    }

    // ---------------------------------------------------------------- cb15 / Option C (the user, 2026-11-20): Party mode's layer

    private val partyDoc = """{"type":"castbar","v":"cb15","n":8,"any":1,"fl":1,"pl":1,"show":1,"ps":1,"pick":8,"pp":[39,19],
        "e":[{"s":1,"id":403,"i":136048,"c":0},{"s":2,"id":331,"i":136052,"c":1},{"s":3,"id":324,"i":136051,"c":2}],
        "f":[{"s":1,"id":8042,"i":136026,"c":9},{"s":2,"id":324,"i":136051,"c":10}],
        "p":[{"s":1,"id":331,"i":136052,"c":17},{"s":4,"k":"item","id":118,"n":"Minor Healing Potion","i":134829,"c":20}]}"""

    @Test fun thePartyLayerIsParsed() {
        val b = bar(partyDoc)
        assertTrue(b.partyLayer); assertTrue(b.pshow)
        assertEquals(331, b.pslots[0].id); assertEquals(17, b.pslots[0].cell)
        assertEquals(CastStripModel.Kind.ITEM, b.pslots[3].kind); assertEquals(20, b.pslots[3].cell)
        assertFalse(b.pslots[1].filled)
        assertEquals(16, CastStripModel.PARTY_CELL0)
        val old = bar(fightDoc)
        assertFalse("an older add-on: no party layer", old.partyLayer)
    }

    @Test fun partyThenInCombatThenOwnPerSlot() {
        val b = bar(partyDoc)
        // layers_party_rule.png's four cases (slot 1 all three, slot 2 fight + own, slot 3 own only, slot 4 party over an empty slot)
        fun ids(fight: Boolean, party: Boolean) = (0..3).map { CastStripModel.shownSlot(b, it, fight, party)?.id }
        assertEquals("out of combat", listOf(403, 331, 324, null), ids(false, false))
        assertEquals("in a fight", listOf(8042, 324, 324, null), ids(true, false))
        assertEquals("Party mode", listOf(331, 331, 324, 118), ids(false, true))
        assertEquals("Party mode in a fight: the empty party slot 2 falls through to In combat", listOf(331, 324, 324, 118), ids(true, true))
        assertEquals("the cell tapped is the layer's", "castbar" to 17, CastStripModel.tapCell(b, 0, true, true))
        assertEquals("castbar" to 10, CastStripModel.tapCell(b, 1, true, true))
        assertEquals("p", CastStripModel.shownLayer(b, 0, true, true)); assertEquals("f", CastStripModel.shownLayer(b, 1, true, true))
        assertNull(CastStripModel.shownLayer(b, 2, true, true))
        val off = bar(partyDoc.replace("\"ps\":1,", ""))
        assertEquals("Bar in Party mode off (not armed): the party slots are not used", 403, CastStripModel.shownSlot(off, 0, false, true)?.id)
    }

    @Test fun partyModeKeepsTheBarInFights() {
        val hide = CastStripModel.Combat.HIDE
        assertTrue(CastStripModel.hiddenIn(hide, true, partyOn = false, partyShow = true))
        assertFalse("Party mode on with Bar in Party mode on: shown in fights", CastStripModel.hiddenIn(hide, true, partyOn = true, partyShow = true))
        assertTrue("Bar in Party mode off: Show in combat's rule", CastStripModel.hiddenIn(hide, true, partyOn = true, partyShow = false))
        assertFalse(CastStripModel.hiddenIn(hide, false, partyOn = false, partyShow = false))
    }

    @Test fun theSecondPageOfCodes() {
        assertEquals(listOf("padtab" to 39, "padtab" to 38, "padtab" to 1), CastStripModel.ctlPartyCells())
        assertEquals(listOf("padtab" to 39, "padtab" to 38, "padtab" to 2), CastStripModel.partyShowCells(true))
        assertEquals(listOf("padtab" to 39, "padtab" to 38, "padtab" to 3), CastStripModel.partyShowCells(false))
    }

    @Test fun theBindBoxWalksSixStops() {
        val t = CastStripModel.BIND_TABS
        assertEquals(listOf(0, 1, 2, 3, 4, 5), t.map { it.index })
        assertEquals(listOf(0, 0, 1, 1, 2, 2), t.map { it.situation })
        var at = t[0]
        val seen = arrayListOf(at)
        repeat(6) { at = CastStripModel.stepBind(at, 1, tap = true, fightTap = true, party = true, partyTap = true); seen += at }
        assertEquals("RB: each situation's controller, then its bar; it stops at the last", listOf(t[0], t[1], t[2], t[3], t[4], t[5], t[5]), seen)
        assertEquals("Party mode not allowed: four", t[3], CastStripModel.stepBind(t[3], 1, tap = true, fightTap = true, party = false))
        assertEquals("no party layer: Party mode's controller only", t[4], CastStripModel.stepBind(t[4], 1, tap = true, fightTap = true, party = true, partyTap = false))
        assertEquals("LB from Party mode's controller: In combat's bar", t[3], CastStripModel.stepBind(t[4], -1, tap = true, fightTap = true, party = true, partyTap = true))
        assertEquals(listOf("padtab" to 39, "padtab" to 38, "padtab" to 4), CastStripModel.bindTabCells(t[4], null))
        assertEquals(listOf("padtab" to 39, "padtab" to 38, "padtab" to 5), CastStripModel.bindTabCells(t[5], 10 to 11))
        assertTrue(CastStripModel.bindPartyTab(bar(partyDoc))); assertFalse(CastStripModel.bindPartyTab(bar(fightDoc)))
        val c = SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b71","ctl":{"on":true,"mode":"bind","bind":331,"bpm":1}}"""))!!
        assertTrue(c.bindParty); assertFalse(c.bindCbParty)
        val d = SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b71","ctl":{"on":true,"mode":"bind","bind":331,"bcb":2,"bcp":1}}"""))!!
        assertTrue(d.bindCbParty); assertEquals(2, d.bindCb)
    }

    @Test fun theRoomsDocumentCarriesTheSituations() {
        val c = PadMapModel.parseCtl(JSONObject("""{"type":"padtabctl","v":"p56","c":{"on":true,"focus":"buttons","view":"pbar","top":"party",
            "tcur":5,"cbpn":3,"cbps":1,"pbar":{"ppos":5,"pset":2}}}"""))!!
        assertEquals("pbar", c.view); assertTrue(c.onBar); assertEquals("party", c.top)
        assertEquals(3, c.cbpn); assertTrue(c.cbPShow); assertEquals(5, c.pbarPos); assertEquals(2, c.pbarSet)
        assertEquals("party", PadMapModel.topOf("pbar")); assertEquals("pbar", PadMapModel.barOf("party"))
        assertEquals("castbar", PadMapModel.barOf("normal")); assertEquals("cbfight", PadMapModel.barOf("combat")); assertNull(PadMapModel.barOf("radial"))
        assertEquals(8, PadMapModel.tabOf("pbar"))
        val off = PadMapModel.parseCtl(JSONObject("""{"type":"padtabctl","v":"p56","c":{"on":true,"view":"party","cbpn":0}}"""))!!
        assertFalse("the add-on's copy: off", off.cbPShow)
        val old = PadMapModel.parseCtl(JSONObject("""{"type":"padtabctl","v":"p55","c":{"on":true,"view":"normal"}}"""))!!
        assertTrue("an older add-on: on (the default)", old.cbPShow)
    }

    @Test fun aTopBarSlidesUpBehindTheBand() {
        val laid = CastStripModel.Box(8f, 234f, 616f, 366f)
        val up = CastStripModel.hiddenAbove(laid, 8f)
        assertEquals(CastStripModel.Box(8f, 94f, 616f, 226f), up)
        // the solver lays the hidden top bar with this very helper (its slide is Home's one motion between the two laid boxes)
        val s = HomeLayoutSolver.places(HomeLayout(cast = HomeLayout.CastDock.ABOVE_CHAT), HomeLayoutSolver.State(castHidden = true))
        val laidS = HomeLayoutSolver.places(HomeLayout(cast = HomeLayout.CastDock.ABOVE_CHAT), HomeLayoutSolver.State()).boxes.getValue(HomePart.STRIP)
        val hid = CastStripModel.hiddenAbove(CastStripModel.Box(laidS.l, laidS.t, laidS.r, laidS.b), 8f)
        assertEquals(HomeLayoutSolver.Box(hid.l, hid.t, hid.r, hid.b), s.boxes.getValue(HomePart.STRIP))
    }
}
