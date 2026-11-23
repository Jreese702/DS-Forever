package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * EMPTY LAYER SLOTS DRAW BLANK (the user, 2026-11-23, on 1.0.39: the In combat tab's tap-to-cast slots looked grey with Show
 * tap-to-cast on, Party mode's too; the ruling "No caption needed, just show them blank and be consistent like the controller bar out
 * of combat"). An empty slot of In combat's fight layer / Party mode's party layer used to draw the spell it falls through to, faded;
 * now it draws exactly as an empty Out of combat slot (the empty frame and its number), on the Controller room's pages and in the
 * Bind box. A set slot keeps its badge. What is cast is unchanged (CastStripModel falls through, tested there).
 *
 * The proof is pixels: each empty layer slot's tile is compared with the same tile on Out of combat with an empty bar. With
 * EMPTY_SLOTS_SHOTS set the renders are saved there too (CLOCK_ART_ROOT, as ControllerSituationsShotsTest, for the real art).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControllerEmptySlotsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val out: File? = System.getenv("EMPTY_SLOTS_SHOTS")?.let { File(it).also { d -> d.mkdirs() } }
    private val w = 1240
    private val h = 960

    private var sideWas = CastStripModel.Side.OFF
    private var combatWas = CastStripModel.Combat.HIDE
    private var partyWas = true
    @Before fun up() {
        System.getenv("CLOCK_ART_ROOT")?.let { root ->
            val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, File(root))
        }
        seedIcons()
        HomeSettings.init(app); sideWas = HomeSettings.castBarSide; combatWas = HomeSettings.castBarCombat; partyWas = HomeSettings.castBarPartyShow
        HomeSettings.castBarSide = CastStripModel.Side.BOTH; HomeSettings.castBarCombat = CastStripModel.Combat.STAY; HomeSettings.castBarPartyShow = true
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
    }
    /** the bar's icons as plain colour squares when no art root has them (a set slot must show a picture, the art or not) */
    private fun seedIcons() {
        val ids = Regex("\"i\":([0-9]+)").findAll(ControllerSituationsShotsTest.BAR).map { it.groupValues[1].toInt() }.toSet()
        val f = Icons::class.java.getDeclaredField("cache"); f.isAccessible = true
        @Suppress("UNCHECKED_CAST") val cache = f.get(Icons) as android.util.LruCache<Int, Bitmap>
        for ((n, id) in ids.withIndex()) if (Icons.get(id) == null)
            cache.put(id, Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(40 + n * 20, 200 - n * 15, 90)) })
    }
    @After fun down() { HomeSettings.castBarSide = sideWas; HomeSettings.castBarCombat = combatWas; HomeSettings.castBarPartyShow = partyWas }

    /** the shots test's Shaman bar with its layers: [fight] / [party] = the layers' entries (null = that layer empty) */
    private fun bar(own: Boolean = true, fight: Boolean = false, party: Boolean = false): CastStripModel.Bar {
        val d = JSONObject(ControllerSituationsShotsTest.BAR)
        if (!own) d.put("e", JSONArray())
        if (!fight) d.put("f", JSONArray())
        if (!party) d.put("p", JSONArray())
        return CastStripModel.parse(d)!!
    }

    private fun stand(): PadMapModel.Map = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m176","m":{"page":1,"pages":3,
        "sets":[{"set":1,"slots":[{"pos":4,"s":4,"kind":"none"},{"pos":1,"s":1,"kind":"none"}]}]}}"""))!!

    private fun ctl(view: String, focus: String = "buttons", pos: Int = 1, extra: String = ""): PadMapModel.Ctl {
        val top = PadMapModel.topOf(view)
        val tcur = PadMapModel.TABS.indexOf(top) + 1
        return PadMapModel.parseCtl(JSONObject("""{"type":"padtabctl","v":"p56","c":{"on":true,"focus":"$focus","set":1,"shown":1,
            "pos":$pos,"page":1,"view":"$view","top":"$top","lay":"swap","tcur":$tcur,"sw":1,"cbpn":0,"cbps":1$extra}}"""))!!
    }

    private var last: ControllerView? = null
    /** the Controller room on [view] with [b] live, drawn (twice: the tab gaps' first frame settles) */
    private fun room(b: CastStripModel.Bar, c: PadMapModel.Ctl, shot: String? = null): Bitmap {
        CastStripModel.Live.set(b)
        val v = ControllerView(app, { true })
        v.update(SpellsModel.parse(JSONObject(ControllerSituationsShotsTest.BOOK))!!)
        v.onPadMap(stand())
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        v.onPadTabCtl(c)
        last = v
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp)); v.draw(Canvas(bmp))
        save(bmp, shot)
        return bmp
    }

    private fun save(b: Bitmap, name: String?) {
        if (out == null || name == null) return
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** the cursor on the controller, never on the bar: no focus ring on any tile */
    private fun onController(view: String) = if (view == "party") ctl("party", focus = "pdiam", extra = ""","ppos":8""") else ctl(view, pos = 8)

    /** slot [p]'s tile with room for its own badge (over the top-right corner, out to 9 px past it) and its number under it; never
     *  the slot before's badge (it ends 3 px short of this tile) */
    private fun tileArea(p: Int): RectF = SituationPage.tile(p).let { RectF(it.left - 2f, it.top - 12f, it.right + 10f, SituationPage.NUM_Y + 8f) }

    private fun same(a: Bitmap, b: Bitmap, r: RectF): Boolean {
        for (y in r.top.toInt() until r.bottom.toInt()) for (x in r.left.toInt() until r.right.toInt())
            if (a.getPixel(x, y) != b.getPixel(x, y)) return false
        return true
    }

    @Test fun emptyLayerSlotsDrawLikeEmptyOutOfCombatSlots() {
        // the reference: Out of combat with nothing on the bar (eight empty frames and their numbers)
        val ref = room(bar(own = false), onController("normal"), "a0_out_of_combat_empty_bar")
        room(bar(), onController("normal"), "a1_out_of_combat")
        for (view in listOf("combat", "party")) {
            if (view == "party" && !GameUi.partyModeOn()) continue
            val tag = if (view == "combat") "b" else "c"
            val word = if (view == "combat") "in_combat" else "party_mode"
            // the layer empty, the bar's own slots all set beneath it: every slot blank, as Out of combat's empty ones
            val empty = room(bar(), onController(view), "${tag}1_${word}_empty_layer")
            for (p in 1..8) assertTrue("$view: empty slot $p draws as an empty Out of combat slot", same(ref, empty, tileArea(p)))
            // partly set (slot 1, and 2 for In combat): the set ones wear the badge, the rest blank
            val part = room(bar(fight = view == "combat", party = view == "party"), onController(view), "${tag}2_${word}_partly_set")
            assertFalse("$view: the set slot 1 shows its thing", same(ref, part, tileArea(1)))
            for (p in (if (view == "combat") 3 else 2)..8) assertTrue("$view: empty slot $p blank beside set ones", same(ref, part, tileArea(p)))
        }
    }

    @Test fun anEmptyLayerSlotsDetailReadsAsOutOfCombats() {
        // the cursor on empty slot 3: the right page's name and help line read as on Out of combat (the "On" line names the
        // situation, above them); "Empty: slot 3's own in fights" is gone
        val rightHelp = RectF(780f, 410f, w - 56f, 690f)
        val ref = room(bar(own = false), ctl("castbar", pos = 3), "a2_out_of_combat_empty_slot_cursor")
        for ((view, cbv) in listOf("combat" to "cbfight", "party" to "pbar")) {
            if (view == "party" && !GameUi.partyModeOn()) continue
            val extra = if (view == "party") ""","pbar":{"ppos":8,"pset":1}""" else ""
            val tag = if (view == "combat") "b" else "c"
            val word = if (view == "combat") "in_combat" else "party_mode"
            val v = room(bar(), ctl(cbv, pos = 3, extra = extra), "${tag}3_${word}_empty_slot_cursor")
            assertTrue("$cbv: the empty slot's help line as Out of combat's", same(ref, v, rightHelp))
            assertEquals("$cbv: the empty slot's name", "Empty", last!!.page().shownName())
        }
    }

    // ------------------------------------------------------------------ the Bind box (Spellbook / Bags / Macros)
    private fun box(b: CastStripModel.Bar, extra: String, shot: String?): Pair<Bitmap, SpellsOverlay> {
        CastStripModel.Live.set(b)
        val o = SpellsOverlay(app, otherReader = { true })
        o.onBook(SpellsModel.parse(JSONObject(ControllerSituationsShotsTest.BOOK))!!)
        o.onCtl(SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b71","ctl":{"on":true,"mode":"bind","bind":8017$extra}}"""))!!)
        val hh = 1080 - Strip.HEIGHT
        o.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hh, View.MeasureSpec.EXACTLY))
        o.layout(0, 0, w, hh)
        val bmp = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Theme.ink); o.draw(c)
        save(bmp, shot)
        return bmp to o
    }
    private fun boxSlot(o: SpellsOverlay, i: Int): RectF {
        val m = SpellsOverlay::class.java.getDeclaredMethod("cbSlotRect", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        m.isAccessible = true
        // its own badge reaches 18 px past its right edge (the swords): 2 px into the next slot, so each area starts 3 px in
        return (m.invoke(o, i, w.toFloat(), 0f) as RectF).let { RectF(it.left + 3f, it.top - 20f, it.right + 19f, it.bottom + 6f) }
    }

    @Test fun theBindBoxsEmptyLayerSlotsDrawBlankToo() {
        // the stop on slot 8 (where the spell would go); slots 1-7 compared with Out of combat's empty bar
        val (ref, o) = box(bar(own = false), ""","bcb":8""", "d0_bindbox_out_of_combat_empty_bar")
        box(bar(), ""","bcb":8""", "d1_bindbox_out_of_combat")
        for ((key, name) in listOf("bcf" to "in_combat", "bcp" to "party_mode")) {
            val empty = box(bar(), ""","bcb":8,"$key":1""", "d2_bindbox_${name}_empty_layer").first
            for (i in 0..6) assertTrue("$name: Bind box empty slot ${i + 1} blank", same(ref, empty, boxSlot(o, i)))
            val part = box(bar(fight = key == "bcf", party = key == "bcp"), ""","bcb":8,"$key":1""", "d3_bindbox_${name}_partly_set").first
            assertFalse("$name: the set slot 1 shows its thing", same(ref, part, boxSlot(o, 0)))
            for (i in (if (key == "bcf") 2 else 1)..6) assertTrue("$name: Bind box empty slot ${i + 1} blank beside set ones", same(ref, part, boxSlot(o, i)))
        }
    }
}
