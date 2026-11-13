package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub #43, THE TAP-TO-CAST BAR (the app half; the add-on half is ActionBar.lua cb1, proven by tools/test_cast_bar.py): the `castbar`
 * document, the `castcd` cooldowns, the ONE tap per slot, the Spellbook's toggle (the pick, then the spell's two taps), the Settings
 * default (shown only while the bar has spells), and the rules the page keeps (no page text, no per-row button, menus translated).
 */
class CastStripModelTest {
    private fun bar(json: String) = CastStripModel.parse(JSONObject(json))!!

    private val two = """{"type":"castbar","v":"cb1","n":12,"pick":12,"e":[{"s":1,"id":403,"i":136048,"c":0},
        {"s":3,"id":331,"i":136052,"q":1},{"s":5,"q":1}]}"""

    @Test fun theDocumentReadsTwelveSlots() {
        val b = bar(two)
        assertEquals(12, b.slots.size)
        assertEquals(CastStripModel.Slot(0, 403, 136048, 0, false), b.slots[0])
        assertEquals(CastStripModel.Slot(2, 331, 136052, null, true), b.slots[2])
        assertEquals(CastStripModel.Slot(4, null, null, null, true), b.slots[4])        // emptied by a queued edit: dimmed
        assertEquals(CastStripModel.Slot(1, null, null, null, false), b.slots[1])
        assertEquals(12, b.pick)
        assertTrue(b.hasSpells); assertTrue(b.has(403)); assertTrue(b.has(331)); assertFalse(b.has(324)); assertFalse(b.full)
        assertEquals("cb1", b.version)
        assertNull(CastStripModel.parse(JSONObject("""{"type":"facecd"}""")))
    }

    @Test fun anEmptyBarOrNoPadRow() {
        val empty = bar("""{"type":"castbar","v":"cb1","n":12,"pick":12,"e":[]}""")
        assertFalse(empty.hasSpells)
        val obj = bar("""{"type":"castbar","v":"cb1","n":12,"pick":12,"e":{}}""")     // the encoder's empty table may be an object
        assertFalse(obj.hasSpells)
        val noPad = bar("""{"type":"castbar","v":"cb1","n":12,"nopad":1,"e":[{"s":1,"id":403,"q":1}]}""")
        assertNull(noPad.pick)
        assertNull(CastStripModel.tapCell(noPad, 0))
        // bad entries are dropped, never shifted onto another slot
        val bad = bar("""{"type":"castbar","pick":12,"e":[{"s":0,"id":1,"c":0},{"s":13,"id":2,"c":12},{"s":2,"id":5,"c":40}]}""")
        assertEquals(listOf(null, 5), bad.slots.take(2).map { it.id })
        assertNull(bad.slots[1].cell)
    }

    @Test fun aTapIsOneCellOnTheCastbarRow() {
        val b = bar(two)
        assertEquals("castbar" to 0, CastStripModel.tapCell(b, 0))
        assertNull(CastStripModel.tapCell(b, 1))           // empty
        assertNull(CastStripModel.tapCell(b, 2))           // waiting for the fight's end
        assertNull(CastStripModel.tapCell(b, 4))
        assertNull(CastStripModel.tapCell(b, 12))
        assertNull(CastStripModel.tapCell(null, 0))
    }

    @Test fun theSpellbooksToggleIsThePickThenTheSpell() {
        val b = bar(two)
        val spell = listOf("spells" to 20, "spells" to 31)
        assertEquals(listOf("castbar" to 12) + spell, CastStripModel.toggleCells(b, 324, spell))
        assertEquals(listOf("castbar" to 12) + spell, CastStripModel.toggleCells(b, 403, spell))      // on the bar: the same taps take it off
        assertNull(CastStripModel.toggleCells(b, 324, null))
        assertNull(CastStripModel.toggleCells(null, 324, spell))
        val full = bar("""{"type":"castbar","pick":12,"e":[${(1..12).joinToString(",") { """{"s":$it,"id":${1000 + it},"c":${it - 1}}""" }}]}""")
        assertTrue(full.full)
        assertNull(CastStripModel.toggleCells(full, 324, spell))                                       // full: nothing to put it on
        assertEquals(listOf("castbar" to 12) + spell, CastStripModel.toggleCells(full, 1003, spell))   // but one on it can come off
    }

    // review: the add-on stores the id Book's taps carry (the book item's action id); an overridden spell's spellID differs from it
    @Test fun anOverriddenSpellIsFoundByItsActionId() {
        val b = bar(two)
        assertEquals(403, CastStripModel.entryId(403, 77777))
        assertEquals(77777, CastStripModel.entryId(0, 77777))
        assertTrue(b.hasEntry(403, 77777))
        assertTrue(b.hasEntry(0, 403))
        assertFalse(b.hasEntry(324, 77777))
        val full = bar("""{"type":"castbar","pick":12,"e":[${(1..12).joinToString(",") { """{"s":$it,"id":${1000 + it},"c":${it - 1}}""" }}]}""")
        assertNotNull(CastStripModel.toggleCells(full, CastStripModel.entryId(1003, 88888), listOf("spells" to 20, "spells" to 31)))
    }

    @Test fun theSettingsDefaultFollowsTheSpells() {
        val b = bar(two)
        val empty = bar("""{"type":"castbar","pick":12,"e":[]}""")
        assertTrue(CastStripModel.shown(null, b))
        assertFalse(CastStripModel.shown(null, empty))
        assertFalse(CastStripModel.shown(null, null))
        assertTrue(CastStripModel.shown(true, empty))
        assertFalse(CastStripModel.shown(false, b))
    }

    @Test fun twoRowsOfSix() {
        assertEquals(0 to 0, CastStripModel.gridOf(0))
        assertEquals(0 to 5, CastStripModel.gridOf(5))
        assertEquals(1 to 0, CastStripModel.gridOf(6))
        assertEquals(1 to 5, CastStripModel.gridOf(11))
    }

    @Test fun theCooldowns() {
        val m = CastStripModel.parseCd(JSONObject("""{"type":"castcd","v":"cb1","k":{"1":{"d":1.5,"l":1.5,"g":true},
            "3":{"d":8,"l":6,"e":true},"13":{"d":4,"l":2},"4":{"d":4,"l":9}}}"""))!!
        assertEquals(setOf(0, 2), m.keys)
        assertTrue(m[0]!!.gcd); assertFalse(m[0]!!.estimated)
        assertTrue(m[2]!!.estimated)
        assertEquals(emptyMap<Int, PadMapModel.FaceCd>(), CastStripModel.parseCd(JSONObject("""{"type":"castcd","v":"cb1"}""")))
        assertNull(CastStripModel.parseCd(JSONObject("""{"type":"facecd"}""")))
        // the sweep on the app's clock: 8 s long, 6 s left when it arrived at t = 10 000
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

    /** the clutter rule: the Spellbook's list rows get no button; Cast bar is the selected spell's one action beside Bind */
    @Test fun castBarIsTheSelectedSpellsActionNotARowButton() {
        val s = src("SpellbookView.kt")
        assertEquals(1, Regex("""UiKit\.button\([^)]*"Cast bar"""").findAll(s).count())
        val draw = s.substring(s.indexOf("// GitHub #43: Cast bar, beside Bind"))
        assertTrue(draw.indexOf("castRect(w, h)") in 0..300)
    }

    @Test fun theMenuWordsAreInSpanishAndFrench() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { java.io.File(it) }.first { it.isDirectory }
        for (l in listOf("es", "fr")) {
            Tr.load(l, java.io.File(dir, "$l.json").readText(Charsets.UTF_8))
            for (w in listOf("Tap-to-cast bar", "Your Spellbook picks, one tap each.", "Cast bar", "On", "Off"))
                assertTrue("$l: $w", Tr.t(w, l) != w)
        }
    }
}
