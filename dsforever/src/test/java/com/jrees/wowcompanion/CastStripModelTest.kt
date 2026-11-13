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
 * Left / Right / Both, Off by default, the old Home row's On -> Left), its place on Home per mode, cb4's pages (Left / Right 3 at a
 * time at Both's size; a swipe flips them either way and never moves the bar; a tap still casts, page 2's slots their own cells), the Bind box's Cast bar tab (only while the setting is on), and the rules the page keeps (no page text, menus translated).
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
        // cb4: Left / Right show 3 at a time, Both all 6; every mode's slots ONE size (the user: "the same size as the both option")
        assertEquals(3, CastStripModel.perPage(L)); assertEquals(3, CastStripModel.perPage(R)); assertEquals(6, CastStripModel.perPage(BOTH))
        assertEquals(2, CastStripModel.pages(L)); assertEquals(2, CastStripModel.pages(R)); assertEquals(1, CastStripModel.pages(BOTH))
        assertEquals(0, CastStripModel.perPage(OFF)); assertEquals(0, CastStripModel.pages(OFF))
        assertEquals(l.h, b.h + CastStripModel.DOTS_H, 0.01f)                 // the same slots; Left / Right add the dots' band
        val sl = CastStripModel.slotBox(L, 0, l.w, l.h)
        val sr = CastStripModel.slotBox(R, 0, r.w, r.h)
        val sb = CastStripModel.slotBox(BOTH, 0, b.w, b.h)
        assertEquals(CastStripModel.MAX_SIDE, sb.w, 0.01f)
        assertEquals(sb.w, sl.w, 0.01f); assertEquals(sb.w, sr.w, 0.01f); assertEquals(sb.h, sl.h, 0.01f)
        for (side in listOf(L, R, BOTH)) {
            val box = if (side == L) l else if (side == R) r else b
            val n = CastStripModel.perPage(side)
            val slots = (0 until n).map { CastStripModel.slotBox(side, it, box.w, box.h) }
            for (s in slots) { assertEquals(slots[0].t, s.t, 0f); assertTrue(s.l >= 0f && s.r <= box.w && s.t >= 0f && s.b <= box.h) }
            for (i in 1 until n) assertTrue("$side $i no overlap", slots[i].l >= slots[i - 1].r)
            // spread evenly across (the same room at each end as half the room between two): Both's look, Left / Right alike
            val between = if (n > 1) slots[1].l - slots[0].r else 0f
            assertEquals("$side", between, (slots[0].l - CastStripModel.PAD) * 2f, 0.01f)
            assertEquals("$side", between, (box.w - CastStripModel.PAD - slots[n - 1].r) * 2f, 0.01f)
        }
        // the page dots: two in the well's foot, under the slots, inside the well; none for Both
        val (dy, dx) = CastStripModel.dots(L, l.w, l.h)!!
        assertEquals(2, dx.size)
        assertTrue("dots $dy under the slots ${sl.b}", dy - CastStripModel.DOT_R >= sl.b + 2f)
        assertTrue("dots $dy above the well's edge", dy + CastStripModel.DOT_R <= l.h - CastStripModel.PAD / 2f)
        assertEquals(CastStripModel.PAD, sl.t, 0.01f)
        assertEquals(l.w / 2f, (dx[0] + dx[1]) / 2f, 0.01f)
        assertNull(CastStripModel.dots(BOTH, b.w, b.h)); assertNull(CastStripModel.dots(OFF, b.w, b.h))
    }

    // ---------------------------------------------------------------- cb4: the pages, the swipe that flips them, the tap that still casts

    @Test fun theTwoPagesHoldSlotsOneToThreeAndFourToSix() {
        assertEquals(listOf(0, 1, 2), (0 until 3).map { CastStripModel.slotIndex(L, 0, it) })
        assertEquals(listOf(3, 4, 5), (0 until 3).map { CastStripModel.slotIndex(R, 1, it) })
        assertEquals(listOf(0, 1, 2, 3, 4, 5), (0 until 6).map { CastStripModel.slotIndex(BOTH, 1, it) })   // Both: one page, all 6
        assertEquals(-1, CastStripModel.slotIndex(L, 0, 3)); assertEquals(-1, CastStripModel.slotIndex(OFF, 0, 0))
        assertEquals(5, CastStripModel.slotIndex(L, 7, 2))                    // a stale page is held to the last
        assertEquals(0, CastStripModel.pageOf(L, 2)); assertEquals(1, CastStripModel.pageOf(L, 3)); assertEquals(0, CastStripModel.pageOf(BOTH, 5))
    }

    @Test fun aSwipeFlipsThePageEitherWay() {
        assertEquals(1, CastStripModel.flipTo(L, 0, 200f, 10f, 250L))         // a sideways drag
        assertEquals(1, CastStripModel.flipTo(L, 0, -200f, 10f, 250L))        // either way flips
        assertEquals(0, CastStripModel.flipTo(L, 1, -200f, -20f, 250L))
        assertEquals(0, CastStripModel.flipTo(R, 1, 200f, 0f, 250L))
        assertEquals(1, CastStripModel.flipTo(R, 0, -200f, 0f, 250L))
        assertEquals(1, CastStripModel.flipTo(L, 0, 60f, 5f, 60L))            // a quick flick
        assertNull(CastStripModel.flipTo(BOTH, 0, 300f, 0f, 200L))            // Both / Off: one page, nothing flips
        assertNull(CastStripModel.flipTo(OFF, 0, 300f, 0f, 200L))
        assertNull(CastStripModel.flipTo(L, 0, 150f, 140f, 200L))             // mostly down: not a swipe
        assertNull(CastStripModel.flipTo(L, 0, 60f, 0f, 900L))                // slow and short: not a swipe
    }

    /** cb4: the swipe never moves the bar: the side is set in Settings only (no swipe-to-side call is left) */
    @Test fun aSwipeNeverChangesTheSide() {
        val p = Prefs(hashMapOf("castBarSide" to "LEFT"))
        HomeSettings.startForTest(p)
        assertEquals(1, CastStripModel.flipTo(HomeSettings.castBarSide, 0, 300f, 0f, 200L))
        assertEquals(L, HomeSettings.castBarSide); assertEquals("LEFT", p.m["castBarSide"])
        assertFalse(src("CastStripModel.kt").contains("fun swipeTo("))
        val home = src("HomeView.kt")
        assertFalse(home.contains("castBarSide ="))                           // Home only reads the setting
        assertFalse(home.contains("castStripSwipe"))
        assertFalse(src("CastStripView.kt").contains("CastStripModel.Side) -> Unit"))
    }

    @Test fun theSlideFollowsTheFingerAndComesHome() {
        val w = 600f
        assertEquals(-120f, CastStripModel.dragOffset(-120f, w), 0f)
        assertEquals(w, CastStripModel.dragOffset(900f, w), 0f)
        assertEquals(480f, CastStripModel.otherOffset(-120f, w), 0f)          // dragged left: the other three come in from the right
        assertEquals(-480f, CastStripModel.otherOffset(120f, w), 0f)
        assertEquals(480f, CastStripModel.flipStart(-120f, w), 0f)            // released: the new page goes on from there to 0
        assertEquals(-480f, CastStripModel.flipStart(120f, w), 0f)
        assertEquals(480f, CastStripModel.slideAt(480f, 0f), 0f)
        assertEquals(0f, CastStripModel.slideAt(480f, 1f), 0f)
        assertTrue(CastStripModel.slideAt(480f, 0.5f) in 0f..240f)
    }

    @Test fun aTapStillCasts() {
        assertFalse(CastStripModel.isSwipe(6f, 3f, 90L))
        assertFalse(CastStripModel.leftTap(6f, 3f))                          // a finger that wobbles is still a tap
        assertNull(CastStripModel.flipTo(L, 0, 6f, 3f, 90L))
        assertTrue(CastStripModel.leftTap(40f, 0f))                          // moved off: no cast on its release
        assertEquals("castbar" to 0, CastStripModel.tapCell(bar(two), 0))
        // page 2's first place is slot 4: its own cell on the castbar row
        val b = bar("""{"type":"castbar","v":"cb3","n":6,"pick":6,"pp":[39,19],"e":[{"s":1,"id":403,"i":136048,"c":0},
            {"s":4,"id":8042,"i":136026,"c":3},{"s":6,"id":2484,"i":136102,"c":5}]}""")
        assertEquals("castbar" to 3, CastStripModel.tapCell(b, CastStripModel.slotIndex(L, 1, 0)))
        assertEquals("castbar" to 5, CastStripModel.tapCell(b, CastStripModel.slotIndex(R, 1, 2)))
        assertNull(CastStripModel.tapCell(b, CastStripModel.slotIndex(L, 1, 1)))      // slot 5 empty
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
                "Three spells under the chat;", "Three spells under the minimap;", "swipe for the other three."))
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
