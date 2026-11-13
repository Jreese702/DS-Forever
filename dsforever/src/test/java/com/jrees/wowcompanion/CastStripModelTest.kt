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
 * tools/test_cast_bar.py): the `castbar` document (cb3: 6 slots), the `castcd` cooldowns, the ONE tap per slot, cb3's setting (Off /
 * Left / Right / Both, Off by default, the old Home row's On -> Left), its place on Home per mode, cb5's scroll bar (Left / Right a
 * strip of 6 with 4 in view at Both's size, stopping anywhere, a light fling, edge arrows by the scroll; a tap casts the slot under the finger), the Bind box's Cast bar tab (only while the setting is on), and the rules the page keeps (no page text, menus translated).
 */
class CastStripModelTest {
    private fun bar(json: String) = CastStripModel.parse(JSONObject(json))!!
    private val L = CastStripModel.Side.LEFT
    private val R = CastStripModel.Side.RIGHT
    private val BOTH = CastStripModel.Side.BOTH
    private val OFF = CastStripModel.Side.OFF

    private val two = """{"type":"castbar","v":"cb3","n":6,"pick":6,"pp":[39,19],"e":[{"s":1,"id":403,"i":136048,"c":0},
        {"s":3,"id":331,"i":136052,"q":1},{"s":5,"q":1}]}"""

    @After fun down() { HomeSettings.startForTest(null) }

    @Test fun theDocumentReadsSixSlots() {
        val b = bar(two)
        assertEquals(6, b.slots.size)
        assertEquals(CastStripModel.Slot(0, 403, 136048, 0, false), b.slots[0])
        assertEquals(CastStripModel.Slot(2, 331, 136052, null, true), b.slots[2])
        assertEquals(CastStripModel.Slot(4, null, null, null, true), b.slots[4])        // emptied by a queued edit: dimmed
        assertEquals(CastStripModel.Slot(1, null, null, null, false), b.slots[1])
        assertEquals(6, b.pick)
        assertTrue(b.hasSpells); assertTrue(b.has(403)); assertTrue(b.has(331)); assertFalse(b.has(324)); assertFalse(b.full)
        assertEquals("cb3", b.version)
        assertEquals(2, b.slotOf(0, 331)); assertEquals(0, b.slotOf(403, 77777)); assertEquals(-1, b.slotOf(324, null))
        assertNull(CastStripModel.parse(JSONObject("""{"type":"facecd"}""")))
        // an older add-on's 12: slots past 6 are dropped, never shifted onto another slot
        val old = bar("""{"type":"castbar","v":"cb2b","n":12,"pick":12,"e":[{"s":2,"id":5,"c":1},{"s":9,"id":7,"c":8}]}""")
        assertEquals(listOf(null, 5, null, null, null, null), old.slots.map { it.id })
    }

    @Test fun anEmptyBarOrNoPadRow() {
        assertFalse(bar("""{"type":"castbar","v":"cb3","n":6,"pick":6,"e":[]}""").hasSpells)
        assertFalse(bar("""{"type":"castbar","v":"cb3","n":6,"pick":6,"e":{}}""").hasSpells)   // the encoder's empty table may be an object
        val noPad = bar("""{"type":"castbar","v":"cb3","n":6,"nopad":1,"pp":[39,19],"e":[{"s":1,"id":403,"q":1}]}""")
        assertNull(noPad.pick)
        assertNull(CastStripModel.tapCell(noPad, 0))
        assertTrue(noPad.pickable)
        val bad = bar("""{"type":"castbar","pick":6,"e":[{"s":0,"id":1,"c":0},{"s":7,"id":2,"c":6},{"s":2,"id":5,"c":40}]}""")
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
        // cb5: Left / Right a strip of all 6 with 4 in view, Both all 6; every mode's slots ONE size (the user: "the same size as the both option")
        assertEquals(4, CastStripModel.visible(L)); assertEquals(4, CastStripModel.visible(R)); assertEquals(6, CastStripModel.visible(BOTH))
        assertEquals(0, CastStripModel.visible(OFF))
        assertTrue(CastStripModel.scrolls(L) && CastStripModel.scrolls(R)); assertFalse(CastStripModel.scrolls(BOTH) || CastStripModel.scrolls(OFF))
        assertEquals(l.h, b.h, 0.01f)                                         // no dots band any more: one height
        val sl = CastStripModel.slotBox(L, 0, l.w, l.h)
        val sr = CastStripModel.slotBox(R, 0, r.w, r.h)
        val sb = CastStripModel.slotBox(BOTH, 0, b.w, b.h)
        assertEquals(CastStripModel.MAX_SIDE, sb.w, 0.01f)
        assertEquals(sb.w, sl.w, 0.01f); assertEquals(sb.w, sr.w, 0.01f); assertEquals(sb.h, sl.h, 0.01f)
        assertEquals(CastStripModel.PAD, sl.t, 0.01f)
        for (side in listOf(L, R, BOTH)) {
            val box = if (side == L) l else if (side == R) r else b
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
        // the 5th and 6th wait off the right edge, beyond the window
        assertTrue(CastStripModel.slotBox(L, 4, l.w, l.h).l >= CastStripModel.window(l.w).second)
        assertEquals(listOf(0, 1, 2, 3), CastStripModel.inView(L, l.w, l.h, 0f))
        assertEquals((0 until 6).toList(), CastStripModel.inView(BOTH, b.w, b.h, 0f))
    }

    // ---------------------------------------------------------------- cb5: the scroll bar (any place, no pages), its arrows, the tap

    private val bw = 608f
    private val bh = CastStripModel.MAX_SIDE + 2 * CastStripModel.PAD

    @Test fun theStripScrollsToAnyPlaceAndHoldsAtItsEnds() {
        val p = CastStripModel.pitch(L, bw)
        assertEquals((bw - 2 * CastStripModel.PAD) / 4f, p, 0.001f)
        assertEquals(2 * p, CastStripModel.maxScroll(L, bw), 0.001f)          // two slots hidden at the start
        assertEquals(0f, CastStripModel.maxScroll(BOTH, 1224f), 0f)           // Both does not scroll
        assertEquals(0f, CastStripModel.maxScroll(L, 0f), 0f)                 // not laid out yet: no scroll, no throw
        assertEquals(0f, CastStripModel.clampScroll(L, bw, -50f), 0f)
        assertEquals(2 * p, CastStripModel.clampScroll(L, bw, 9999f), 0.001f)
        // an arbitrary place between slots stays where it is (no snapping): every slot moves by exactly the scroll
        val o = 0.37f * p + 11f
        assertEquals(o, CastStripModel.clampScroll(L, bw, o), 0f)
        for (i in 0 until 6)
            assertEquals(CastStripModel.slotBox(L, i, bw, bh).l - o, CastStripModel.slotBox(L, i, bw, bh, o).l, 0.001f)
        assertEquals(listOf(0, 1, 2, 3, 4), CastStripModel.inView(L, bw, bh, o))      // part of slot 5 has come in
        // the end: slots 3-6 in view spread evenly, as slots 1-4 were at the start
        val e = CastStripModel.maxScroll(L, bw)
        assertEquals(listOf(2, 3, 4, 5), CastStripModel.inView(L, bw, bh, e))
        assertEquals(CastStripModel.slotBox(L, 0, bw, bh).l, CastStripModel.slotBox(L, 2, bw, bh, e).l, 0.001f)
        assertEquals(CastStripModel.slotBox(L, 3, bw, bh).r, CastStripModel.slotBox(L, 5, bw, bh, e).r, 0.001f)
        // the drag follows the finger (left brings the right-hand slots in), held to the ends
        assertEquals(40f, CastStripModel.dragScroll(L, bw, 0f, -40f), 0f)
        assertEquals(0f, CastStripModel.dragScroll(L, bw, 10f, 300f), 0f)
        assertEquals(e, CastStripModel.dragScroll(L, bw, 10f, -2000f), 0.001f)
        assertEquals(0f, CastStripModel.dragScroll(BOTH, 1224f, 0f, -200f), 0f)
        // the scroll kept for the app's run as slots' shares: the same place after a new layout
        assertEquals(1.25f, CastStripModel.toPos(L, bw, 1.25f * p), 0.0001f)
        assertEquals(1.25f * CastStripModel.pitch(R, 600f), CastStripModel.fromPos(R, 600f, 1.25f), 0.001f)
        assertEquals(2f, CastStripModel.toPos(L, bw, 1e6f), 0.0001f)
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
        for (o in listOf(0f, 0.37f * p, p, 1.61f * p, 2 * p)) for (i in CastStripModel.inView(L, bw, bh, o)) {
            val (x, y) = centre(i, o)
            if (x < CastStripModel.window(bw).first || x > CastStripModel.window(bw).second) continue
            assertEquals("scroll $o slot $i", i, CastStripModel.slotAt(L, x, y, bw, bh, o))
        }
        // at the end, the leftmost place in view is slot 3, the rightmost slot 6
        val e = CastStripModel.maxScroll(L, bw)
        assertEquals(2, CastStripModel.slotAt(L, 40f, bh / 2f, bw, bh, e))
        assertEquals(5, CastStripModel.slotAt(L, bw - 40f, bh / 2f, bw, bh, e))
        assertEquals(-1, CastStripModel.slotAt(L, 2f, bh / 2f, bw, bh, 0f))           // outside the window: nothing
        assertEquals(-1, CastStripModel.slotAt(L, 100f, 0.5f, bw, bh, 0f))            // above the slots' row: nothing
        assertEquals(-1, CastStripModel.slotAt(OFF, 100f, bh / 2f, bw, bh, 0f))
        val b = bar("""{"type":"castbar","v":"cb3","n":6,"pick":6,"pp":[39,19],"e":[{"s":1,"id":403,"i":136048,"c":0},
            {"s":4,"id":8042,"i":136026,"c":3},{"s":6,"id":2484,"i":136102,"c":5}]}""")
        assertEquals("castbar" to 5, CastStripModel.tapCell(b, CastStripModel.slotAt(L, bw - 40f, bh / 2f, bw, bh, e)))
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
        assertTrue(src("SpellsOverlay.kt").contains("sl?.icon?.let { Icons.get(it) ?: UiKit.icon(it) }"))
    }

    // ---------------------------------------------------------------- cb3: the Bind box's Cast bar tab (Book b56)

    @Test fun theBindTabOnlyWhenTheSettingIsOn() {
        val b = bar(two)
        assertTrue(b.slotPick)
        assertFalse(CastStripModel.bindTab(OFF, b))
        assertTrue(CastStripModel.bindTab(L, b)); assertTrue(CastStripModel.bindTab(R, b)); assertTrue(CastStripModel.bindTab(BOTH, b))
        assertFalse(CastStripModel.bindTab(L, null))                         // no document yet
        val older = bar("""{"type":"castbar","v":"cb2b","n":12,"pick":12,"pp":[39,19],"e":[]}""")
        assertFalse(older.slotPick); assertFalse(CastStripModel.bindTab(L, older))     // an older add-on takes no slot pick
        assertEquals(listOf("padtab" to 39, "padtab" to 26), CastStripModel.tabCells())
        assertEquals(listOf("padtab" to 39, "padtab" to 20), CastStripModel.slotCells(0))
        assertEquals(listOf("padtab" to 39, "padtab" to 25), CastStripModel.slotCells(5))
        assertNull(CastStripModel.slotCells(6)); assertNull(CastStripModel.slotCells(-1))
        // LB / RB walk Normal | In combat | Cast bar, no wrap; two tabs only while it is off
        assertEquals(2, CastStripModel.stepTab(1, 1, true)); assertEquals(2, CastStripModel.stepTab(2, 1, true))
        assertEquals(1, CastStripModel.stepTab(2, -1, true)); assertEquals(0, CastStripModel.stepTab(0, -1, true))
        assertEquals(1, CastStripModel.stepTab(1, 1, false))
    }

    @Test fun theBindTabReadsTheAddonsCursor() {
        val c = SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b56","ctl":{"on":true,"mode":"bind","bind":403,"bcb":3}}"""))
        assertEquals(3, c?.bindCb)
        val none = SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b56","ctl":{"on":true,"mode":"bind","bind":403,"bcb":9}}"""))
        assertNull(none?.bindCb)
    }

    @Test fun theCooldowns() {
        val m = CastStripModel.parseCd(JSONObject("""{"type":"castcd","v":"cb3","k":{"1":{"d":1.5,"l":1.5,"g":true},
            "3":{"d":8,"l":6,"e":true},"7":{"d":4,"l":2},"4":{"d":4,"l":9}}}"""))!!
        assertEquals(setOf(0, 2), m.keys)
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

    /** cb3: the Bind box's third tab replaces the Spellbook's own "Cast bar" button; the Settings row is Off / Left / Right / Both */
    @Test fun theThirdTabReplacesTheSpellbooksButton() {
        assertFalse(src("SpellbookView.kt").contains("\"Cast bar\""))
        val o = src("SpellsOverlay.kt")
        assertEquals(1, Regex("""UiKit\.tab\(c, fightRect\(2, w\), "Cast bar", cbOn\)""").findAll(o).count())
        assertTrue(o.contains("CastStripModel.bindTab(HomeSettings.castBarSide"))
        val h = src("HomeSettings.kt")
        assertTrue(h.contains("row(c, y + GAP, \"Tap-to-cast bar\""))
        assertFalse(h.contains("castStrip ="))
    }

    @Test fun theMenuWordsAreInSpanishAndFrench() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { java.io.File(it) }.first { it.isDirectory }
        for (l in listOf("es", "fr")) {
            Tr.load(l, java.io.File(dir, "$l.json").readText(Charsets.UTF_8))
            for (w in listOf("Tap-to-cast bar", "Six spells, one tap each.", "Cast bar", "Off", "Left", "Right", "Both", "Place",
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
}
