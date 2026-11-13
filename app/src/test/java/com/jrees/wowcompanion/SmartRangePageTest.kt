package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * m146 / p46 SMART RANGE ON THE IN COMBAT TAB'S RIGHT PAGE (the user, 2026-11-12: "revise the right section and remove the "Otherwise
 * spell", and instead move down the assigned spell and make space for an optional checkbox called "Smart Range". when enabled I want two
 * selectable buttons, one for melee and one for range, and after selecting that button then the bind and move (if applicable) appear").
 * A finger's plate / Melee / Range / Bind / Move send Book b52's padtab 39 pick + its code (no game action), then the usual taps; the
 * page draws each state. With SR_SHOTS_DIR set (and tools/test_smart_range.py's SR_DUMP=<that dir> docs in <dir>/docs) it also renders
 * the room OFF / ON with nothing picked / ON with both halves set from the add-on's own documents.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmartRangePageTest {
    private val w = 1240f
    private val h = 960f

    // m148: a Hunter's document (`sro` c = 1: the caster's sets have Smart Range)
    private fun map(slot: String) = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m146","m":{"page":1,"pages":3,"sro":{"c":1,"sw":[2973]},
        "sets":[{"set":1,"slots":[$slot]}]}}"""))!!

    private fun ctl(pos: Int, extra: String = "") = PadMapModel.parseCtl(JSONObject("""{"type":"padtabctl","v":"p46","c":{"focus":"buttons",
        "set":1,"shown":1,"pos":$pos,"page":1,"view":"combat","lay":"swap"$extra}}"""))!!

    private class Rig(m: PadMapModel.Map, c: PadMapModel.Ctl) {
        val cells = ArrayList<List<Pair<String, Int>>>()
        val host = View(RuntimeEnvironment.getApplication())
        val page = PadTabPage(host, { cells += it }, {}, { false })
        init { page.onMap(m); page.onCtl(c); draw() }
        fun draw() {
            val b = Bitmap.createBitmap(1240, 960, Bitmap.Config.ARGB_8888)
            page.draw(Canvas(b), 1240f, 960f, null)
        }
        fun tap(x: Float, y: Float) {
            val t = SystemClock.uptimeMillis()
            page.onTouch(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0), 1240f, 960f, null)
            page.onTouch(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0), 1240f, 960f, null)
            draw()
        }
    }

    // the right page's places (PadTabPage: SPLIT 740; the plate y 370-440, Melee | Range y 470-570, Move h-252..h-160, Bind / Change h-141..h-30)
    private val plate = 990f to 405f
    private val melee = 860f to 520f
    private val range = 1100f to 520f
    private val bind = 990f to h - 85f
    private val move = 990f to h - 206f

    private val yOff = """{"pos":6,"kind":"fixed","n":"Inspect","cf":{"n":"Raptor Strike","id":2973,"i":132223}}"""
    private val zrPair = """{"pos":2,"s":26,"kind":"combat","ov":true,"base":"none","sr":true,"c":{"n":"Raptor Strike","id":2973,"i":132223,
        "sr":1,"h":"m","mn":"DSF ZR+Up","rg":{"n":"Arcane Shot","id":3044,"i":132218}}}"""

    @Test fun thePlateFlipsTheBoxByTheSmartRangePick() {
        val r = Rig(map(yOff), ctl(6))
        r.tap(plate.first, plate.second)
        assertEquals(listOf(PadMapModel.srCells(PadMapModel.SR_BOX)), r.cells)
        assertEquals(listOf("padtab" to 39, "padtab" to 0), PadMapModel.srCells(PadMapModel.SR_BOX))
    }

    @Test fun offShowsChangeCombatSpellNoHalves() {
        val r = Rig(map(yOff), ctl(6))
        r.tap(melee.first, melee.second)
        assertTrue("off: no Melee / Range buttons to tap", r.cells.isEmpty())
        r.tap(bind.first, bind.second)
        assertTrue("off: A is Change combat spell (the app's chooser)", r.page.chooserOpen())
    }

    @Test fun onWithNothingPickedHasNoBind() {
        val m = map("""{"pos":6,"kind":"fixed","n":"Inspect","sr":true}""")
        assertTrue(PadMapModel.srOn(PadMapModel.slot(m, 1, 6)))
        val r = Rig(m, ctl(6))
        r.tap(bind.first, bind.second)
        assertFalse("nothing picked: no Bind", r.page.chooserOpen())
        r.tap(move.first, move.second)
        assertTrue("nothing picked: no Move", r.cells.isEmpty())
    }

    @Test fun pickingAHalfThenBindAndMove() {
        val m = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m146","m":{"page":1,"pages":3,"sro":{"c":1},"sets":[{"set":1,"slots":[]},
            {"set":3,"slots":[$zrPair]}]}}"""))!!
        val c = PadMapModel.parseCtl(JSONObject("""{"type":"padtabctl","v":"p46","c":{"focus":"buttons","set":3,"shown":3,"pos":2,"page":1,
            "view":"combat","lay":"swap"}}"""))!!
        val r = Rig(m, c)
        r.tap(range.first, range.second)
        assertEquals("a finger on Range: the pick (padtab 39 + 2), no game action", listOf(PadMapModel.srCells(PadMapModel.SR_RANGE)), r.cells)
        r.cells.clear()
        r.tap(move.first, move.second)
        assertEquals("Move (X) on the picked half: the pick again, then the carry", listOf(PadMapModel.srCells(PadMapModel.SR_RANGE) +
            listOf(PadMapModel.ROW_PADTAB to PadMapModel.COL_CARRY)), r.cells)
        r.tap(melee.first, melee.second)
        r.tap(bind.first, bind.second)
        assertTrue("Bind (A) on the picked half opens the chooser", r.page.chooserOpen())
    }

    @Test fun thePickerAndThePickedHalfComeFromTheAddOn() {
        val c = PadMapModel.parseCtl(JSONObject("""{"type":"padtabctl","v":"p46","c":{"on":true,"focus":"buttons","set":1,"pos":6,"view":"combat",
            "srp":2,"srh":"m","carry":{"set":3,"pos":2,"page":1,"h":"r"}}}"""))!!
        assertEquals(2, c.srp)
        assertEquals("m", c.srh)
        assertEquals("r", c.carry?.h)
        // a Y press is the add-on's (PadTabCtl p46 toggles the box; tools/test_smart_range.py), the page draws its `sr`
        val sl = PadMapModel.slot(PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m146","m":{"page":1,"pages":3,
            "sets":[{"set":3,"slots":[$zrPair]}]}}"""))!!, 3, 2)
        assertEquals("Raptor Strike", PadMapModel.srMelee(sl)?.name)
        assertEquals("Arcane Shot", PadMapModel.srRange(sl)?.name)
        assertEquals("DSF ZR+Up", PadMapModel.fightRef(sl)?.macro)
        val r = Rig(map(yOff), c)
        r.draw()                                                            // the picker's box on Range draws (no crash)
    }

    // ---------------------------------------------------------------- m147 / p47 (review; the user, 2026-11-12: "okay so I was
    // skeptical and so I tried it with a different melee skill, wing clip and it did not work")

    private fun zrMap() = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m147","m":{"page":1,"pages":3,"sro":{"c":1},"sets":[{"set":1,"slots":[]},
        {"set":3,"slots":[$zrPair]}]}}"""))!!
    private fun zrCtl(extra: String = "") = PadMapModel.parseCtl(JSONObject("""{"type":"padtabctl","v":"p47","c":{"focus":"buttons","set":3,
        "shown":3,"pos":2,"page":1,"view":"combat","lay":"swap"$extra}}"""))!!

    @Test fun aHalfsClearGoesStraightOffWithNoBox() {
        val r = Rig(zrMap(), zrCtl())
        r.tap(range.first, range.second)
        r.tap(bind.first, bind.second)
        assertTrue("Bind on Range: the chooser", r.page.chooserOpen())
        r.cells.clear()
        r.tap(990f, (h - 12f) - 196f + 30f)                                // the chooser's pinned Clear row (clearTop = box bottom - 196)
        assertEquals("the Range half's Clear: the half picked again, the combat-layer pick, Clear - in one touch, no box first",
            listOf(PadMapModel.srCells(PadMapModel.SR_RANGE) + listOf(PadMapModel.ROW_LAYOUT to PadMapModel.COL_COMBAT_LAYER,
                PadMapModel.ROW to PadMapModel.COL_CLEAR)), r.cells)
        assertFalse(r.page.chooserOpen())
    }

    @Test fun aTapOnTheMovePageNeverFlipsTheBoxOrPicksAHalf() {
        val r = Rig(zrMap(), zrCtl(""","on":true,"carry":{"set":3,"pos":1,"page":1,"h":"r"}"""))
        r.tap(plate.first, plate.second)
        r.tap(melee.first, melee.second)
        r.tap(range.first, range.second)
        assertTrue("carrying: the plate / Melee / Range taps send nothing", r.cells.isEmpty())
    }

    @Test fun aFacesNoKeyWarningStaysOnTheInCombatTab() {
        fun shot(keys: String): Bitmap {
            val m = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m147","m":{"page":1,"pages":3,"face":{"keys":{$keys}},
                "sets":[{"set":1,"slots":[$yOff]}]}}"""))!!
            val page = PadTabPage(View(RuntimeEnvironment.getApplication()), {}, {}, { false })
            page.onMap(m); page.onCtl(ctl(6))
            val b = Bitmap.createBitmap(1240, 960, Bitmap.Config.ARGB_8888)
            page.draw(Canvas(b), 1240f, 960f, null)
            return b
        }
        assertTrue(PadMapModel.faceUnbound(PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m147","m":{"page":1,"pages":3,
            "sets":[{"set":1,"slots":[$yOff]}]}}"""))!!, PadMapModel.slot(map(yOff), 1, 6)))
        val warned = shot(""); val plain = shot(""""6":"PAD4"""")
        var diff = 0
        for (y in 556 until 612) for (x in 790 until 1180) if (warned.getPixel(x, y) != plain.getPixel(x, y)) diff++
        assertTrue("the 'no key on Y' prose under the In combat row (y 556-612): $diff pixels differ", diff > 200)
    }

    // ---------------------------------------------------------------- m148 / p48 WHO HAS IT (the user, 2026-11-12: "this option should
    // only appear for classes with next swing pairs- and it should only list the abilities that can work with it in the melee options";
    // "for druid this should only appear for the bear form action bar, and that's even if there is ranged attacks that bear druid can use.
    // maybe taunt?")

    private fun noSro(slot: String) = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m148","m":{"page":1,"pages":3,
        "sets":[{"set":1,"slots":[$slot]}]}}"""))!!

    @Test fun aClassWithoutNextSwingAttacksHasNoPlate() {
        // a Paladin's document: no `sro` (an old side-map `sr` ignored by the page too)
        val r = Rig(noSro("""{"pos":6,"kind":"fixed","n":"Inspect","sr":true,"cf":{"n":"Crusader Strike","id":35395,"i":135891}}"""), ctl(6))
        r.tap(plate.first, plate.second)
        r.tap(melee.first, melee.second)
        assertTrue("no plate, no Melee / Range: nothing sent", r.cells.isEmpty())
        r.tap(bind.first, bind.second)
        assertTrue("A is the plain Change combat spell (the app's chooser)", r.page.chooserOpen())
        assertEquals("the chooser lists every spell (no half)", listOf(6807, 6795), r.page.chooserSpellIds(book()))
    }

    private val druidForms = """"forms":[{"x":1,"id":5487,"n":"Bear Form","i":132276},{"x":2,"id":768,"n":"Cat Form","i":132115}]"""
    private val bearUp = """{"f":5487,"p":2,"n":"Maul","id":6807,"i":132136,"sr":1,"h":"m","mn":"DSF Bear Up",
        "rg":{"n":"Growl","id":6795,"i":132270}}"""
    private fun druidMap(ff: String = bearUp, ffsr: String = "") = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m148","m":{"page":1,
        "pages":3,"sro":{"f":[5487],"sw":[6807]},$druidForms,"ff":[$ff]$ffsr,
        "sets":[{"set":1,"slots":[{"pos":2,"s":2,"kind":"none"},$yOff]}]}}"""))!!
    private fun druidCtl(form: Int?, pos: Int) = PadMapModel.parseCtl(JSONObject("""{"type":"padtabctl","v":"p48","c":{"focus":"buttons",
        "set":1,"shown":1,"pos":$pos,"page":1,"view":"combat","lay":"swap"${form?.let { ""","form":$it""" } ?: ""}}}"""))!!
    private fun book() = SpellsModel.parse(JSONObject("""{"type":"spells","v":"s9","s":{"lines":[{"k":1,"n":"General"},{"k":2,"n":"Feral"}],
        "items":[{"n":"Maul","t":1,"id":6807,"a":6807,"sl":1,"ln":2},{"n":"Growl","t":1,"id":6795,"a":6795,"sl":2,"ln":2}]}}"""))

    @Test fun aDruidsCasterButtonsAndCatFormHaveNoPlate() {
        for ((form, pos) in listOf(null to 6, null to 2, 768 to 2)) {
            val r = Rig(druidMap(), druidCtl(form, pos))
            r.tap(plate.first, plate.second)
            assertTrue("form $form pos $pos: no plate", r.cells.isEmpty())
        }
    }

    @Test fun theBearFormSetHasThePlateAndItsHalves() {
        val m = druidMap()
        val r = Rig(m, druidCtl(5487, 2))
        r.tap(plate.first, plate.second)
        assertEquals("Bear's Up: the plate flips its box", listOf(PadMapModel.srCells(PadMapModel.SR_BOX)), r.cells)
        r.cells.clear()
        r.tap(melee.first, melee.second)
        assertEquals("Melee: the pick", listOf(PadMapModel.srCells(PadMapModel.SR_MELEE)), r.cells)
        r.cells.clear()
        r.tap(move.first, move.second)
        assertTrue("no Move of a Bear half (as the base X / Y / A)", r.cells.isEmpty())
        r.tap(bind.first, bind.second)
        assertTrue("Bind on Melee: the chooser", r.page.chooserOpen())
        assertEquals("the Melee chooser: its own Maul left out, Growl never (not a next-swing attack)", emptyList<Int>(), r.page.chooserSpellIds(book()))
        val r2 = Rig(druidMap(ff = """{"f":5487,"p":2,"n":"Growl","id":6795,"i":132270,"sr":1,"h":"r"}"""), druidCtl(5487, 2))
        r2.tap(melee.first, melee.second)
        r2.tap(bind.first, bind.second)
        assertEquals("an empty Melee half: the chooser lists only Maul (the add-on's sw)", listOf(6807), r2.page.chooserSpellIds(book()))
        // the form entry's halves come from the add-on's ff
        val ff = PadMapModel.formFight(m, 5487, 2)!!
        assertEquals("DSF Bear Up", ff.macro)
        assertEquals("Growl", ff.range?.name)
    }

    @Test fun theRangeChooserListsAnything() {
        val r = Rig(druidMap(), druidCtl(5487, 2))
        r.tap(range.first, range.second)
        r.tap(bind.first, bind.second)
        assertEquals("the Range half's chooser: every spell but its own (Growl)", listOf(6807), r.page.chooserSpellIds(book()))
        val r2 = Rig(druidMap(ff = """{"f":5487,"p":2,"n":"Maul","id":6807,"i":132136,"sr":1,"h":"m"}"""), druidCtl(5487, 2))
        r2.tap(range.first, range.second)
        r2.tap(bind.first, bind.second)
        assertEquals("an empty Range half: Maul and Growl", listOf(6807, 6795), r2.page.chooserSpellIds(book()))
    }

    @Test fun aBearBoxWithNoSpellYetIsChecked() {
        val m = druidMap(ff = """{"f":768,"p":5,"n":"Claw","id":1082,"i":132140}""", ffsr = ""","ffsr":[{"f":5487,"p":2}]""")
        assertTrue((5487 to 2) in m.srFormBoxes)
        val r = Rig(m, druidCtl(5487, 2))
        r.tap(melee.first, melee.second)
        assertEquals("the box checked (ffsr): Melee / Range are there", listOf(PadMapModel.srCells(PadMapModel.SR_MELEE)), r.cells)
    }

    @Test fun shots() {
        val out = System.getenv("SR_SHOTS_DIR")?.let { File(it) } ?: return
        val docs = File(out, "docs")
        if (!docs.isDirectory) return
        System.getenv("CTL_ART_ROOT")?.let { root ->
            val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, File(root))
        }
        for (name in listOf("off", "on_none", "on_both", "bear", "bear_melee", "paladin")) {      // m148: a Druid's Bear Form set, a Paladin
            val mf = File(docs, "sr_${name}_map.json"); val cf = File(docs, "sr_${name}_ctl.json")
            if (!mf.isFile || !cf.isFile) continue
            val v = ControllerView(RuntimeEnvironment.getApplication(), { true })
            v.onPadMap(PadMapModel.parse(JSONObject(mf.readText()))!!)
            v.onPadTabCtl(PadMapModel.parseCtl(JSONObject(cf.readText()))!!)
            v.measure(View.MeasureSpec.makeMeasureSpec(w.toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h.toInt(), View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w.toInt(), h.toInt())
            val b = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
            v.draw(Canvas(b))
            File(out, "smart_range_$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
