package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * feat/wk-itemcast (the user, 2026-11-16: "the cast bar for using Items from my bag or applying enchants is not visible"): a cast
 * started from a room - an item used from Bags, the Character room's Enchant / kit pick / Feed Pet, a Spellbook or Macros cast - shows
 * in the rooms' bar's tracking band, from the same hudS.cs / ce documents Home's cast bar reads. The checks always run; the PNGs only
 * when ITEMCAST_SHOTS_DIR is set (ITEMCAST_ART_ROOT = an art root laid out like the app's; ITEMCAST_MACRO_DOCS = the Macros room's
 * documents, tools/test_macro_room.py with MACRO_DUMP, for its picture).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RoomCastShotsTest {
    private val out: File? = System.getenv("ITEMCAST_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT
    /** the game's finish (FlashAnim + FadeOutAnim) and interrupt (HoldFadeOutAnim) lengths, castingbarframe.xml (CastFx, internal) */
    private val FINISH_MS = 500L
    private val FAIL_MS = 1300L

    @Before fun clock() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        System.getenv("ITEMCAST_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
    }

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    private fun settle() { ShadowLooper.idleMainLooper(); Thread.sleep(200); ShadowLooper.idleMainLooper() }
    private fun later(ms: Long) { ShadowSystemClock.advanceBy(Duration.ofMillis(ms)) }

    // ---------------------------------------------------------------- the add-on's documents (ActionBar.lua's hudS)

    private fun g() = SystemClock.elapsedRealtime() / 1000.0
    /** hudS with a cast / channel [p] (0..1) of [d] s in */
    private fun casting(cv: CastView, name: String, d: Double, p: Double, channel: Boolean = false, ni: Boolean = false) {
        val g = g(); val s = g - p * d
        val cs = JSONObject().put("k", if (channel) "h" else "c").put("s", s).put("e", s + d).put("nm", name)
        if (ni) cs.put("n", 1)
        cv.update(JSONObject().put("hudS", JSONObject().put("g", g).put("c", false).put("cs", cs)))
    }
    /** hudS with the end mark: [ok] = done, else interrupted / failed */
    private fun ended(cv: CastView, ok: Boolean, channel: Boolean = false) {
        val g = g()
        val ce = JSONObject().put("x", if (ok) 1 else 0).put("t", g)
        if (channel) ce.put("h", 1)
        cv.update(JSONObject().put("hudS", JSONObject().put("g", g).put("c", false).put("ce", ce)))
    }
    /** hudS in a fight with a swing running and no cast */
    private fun swinging(cv: CastView) {
        val g = g()
        cv.update(JSONObject().put("hudS", JSONObject().put("g", g).put("c", true).put("aa", true)
            .put("sw", JSONObject().put("k", 0).put("s", g - 0.4).put("d", 2.6))))
    }

    /** the rooms' bar as the host wires it (CompanionHost: Home's own bar first, else the cast band) */
    private fun strip(cv: CastView, room: Room, band: Boolean = true) = Strip(app) { }.apply {
        active = room
        xp = Strip.Track("rested", 0.46f, 0.62f)
        if (band) castBand = object : Strip.CastBand {
            override fun state(): Int = if (cv.ownCastBarShown()) 0 else cv.roomCastState()
            override fun draw(c: Canvas, bar: RectF, nameL: Float, nameR: Float): Long = cv.drawRoomCast(c, bar, nameL, nameR)
        }
    }
    private fun drawStrip(s: Strip): Bitmap {
        lay(s, W, Strip.HEIGHT)
        val b = Bitmap.createBitmap(W, Strip.HEIGHT, Bitmap.Config.ARGB_8888)
        s.draw(Canvas(b))
        return b
    }

    // ---------------------------------------------------------------- checks (always)

    @Test fun aCastInARoomTakesTheTrackingBandAndGivesItBack() {
        val cv = CastView(app) { }
        var told = 0
        cv.onCastState = { told++ }
        assertFalse("parked in the rooms' container: never Home's own bar", cv.ownCastBarShown())
        val s = strip(cv, Room.BAGS)
        drawStrip(s)
        assertTrue("no cast: the tracking bar as it was", s.castBox.isEmpty)
        assertEquals(0, cv.roomCastState())

        // Bags' Use on a bandage: First Aid, an 8 s channel
        casting(cv, "First Aid", 8.0, 0.25, channel = true)
        assertEquals(1, told)
        assertEquals(1, cv.roomCastState())
        drawStrip(s)
        val box = s.castBox
        assertFalse("drawn", box.isEmpty)
        // inside the tracking band: never over the room above (y < 0) nor on the rooms' pictures under it
        assertTrue("band ${box.top}..${box.bottom}", box.top >= 0f && box.bottom <= Strip.TRACKS.toFloat())
        for (r in Room.values()) if (r != Room.HOME) s.place(r)?.let { pic ->
            assertFalse("over ${r.name}'s picture", RectF.intersects(box, pic))
        }
        // the same document again (the 0.5 s state window re-sending it): no redraw asked
        val again = told
        casting(cv, "First Aid", 8.0, 0.25, channel = true)
        assertEquals("the same cast (start and end unchanged): no redraw asked", again, told)

        // done: the game's finish plays over the tracking bar, then the band is gone
        later(6100)
        ended(cv, ok = true, channel = true)
        assertTrue(told > again)
        assertEquals(2, cv.roomCastState())
        drawStrip(s)
        assertFalse(s.castBox.isEmpty)
        later(FINISH_MS)
        assertEquals(0, cv.roomCastState())
        drawStrip(s)
        assertTrue("gone after the finish", s.castBox.isEmpty)
    }

    @Test fun anInterruptShowsItsRedBarThenGoes() {
        val cv = CastView(app) { }
        casting(cv, "Disenchant", 3.0, 0.6)
        assertEquals(1, cv.roomCastState())
        ended(cv, ok = false)
        assertEquals(2, cv.roomCastState())
        later(1200)
        assertEquals("the interrupt holds a second", 2, cv.roomCastState())
        later(FAIL_MS - 1200)
        assertEquals(0, cv.roomCastState())
    }

    @Test fun aSwingAloneNeverShowsInARoom() {
        val cv = CastView(app) { }
        swinging(cv)
        assertEquals(0, cv.roomCastState())
        val s = strip(cv, Room.CHARACTER)
        drawStrip(s)
        assertTrue(s.castBox.isEmpty)
        // a cast the game never ended (its end passed, no `ce` yet): gone 100 ms after its own end
        casting(cv, "Hearthstone", 10.0, 0.99)
        assertEquals(1, cv.roomCastState())
        later(220)
        assertEquals(0, cv.roomCastState())
    }

    /**
     * review fix: Home's own bar can leave the screen (a room opens, a Home module covers the band, the band's FULL look) with no cast
     * event; the rooms' bar must be told then, or a cast already running shows on neither until the next cast event (the strip's own
     * clock stops when the living disc is not drawn)
     */
    @Test fun homesOwnBarLeavingTellsTheRoomsBar() {
        val cv = CastView(app) { }
        var told = 0
        cv.onCastState = { told++ }
        cv.layout = CastView.Layout.ROW
        assertEquals("a band layout: re-asked", 1, told)
        cv.bottomLine = true
        assertEquals("Home's line on: re-asked", 2, told)
        cv.bottomLine = true
        assertEquals("no change: nothing", 2, told)
        cv.onVisibilityAggregated(false)
        assertEquals("off the screen: re-asked", 3, told)
        cv.onVisibilityAggregated(true)
        assertEquals("back: re-asked", 4, told)
        cv.layout = CastView.Layout.FULL
        assertEquals("the FULL look draws no cast bar: re-asked", 5, told)
        assertFalse(cv.ownCastBarShown())
    }

    @Test fun theBarAsksForFramesOnlyWhileItMoves() {
        val cv = CastView(app) { }
        val b = Bitmap.createBitmap(W, 40, Bitmap.Config.ARGB_8888)
        val bar = RectF(8f, 5f, W - 8f, 29f)
        assertEquals(-1L, cv.drawRoomCast(Canvas(b), bar, 22f, 534f))
        casting(cv, "Minor Wizard Oil", 3.0, 0.4)
        assertEquals(0L, cv.drawRoomCast(Canvas(b), bar, 22f, 534f))
    }

    // ---------------------------------------------------------------- the pictures (ITEMCAST_SHOTS_DIR)

    private fun l(t: String, c: String = "ffffff") = JSONObject().put("t", t).put("c", c)
    private fun item(slot: Int, id: Int, icon: Int, name: String, q: Int, count: Int = 1) =
        JSONObject().put("bag", 0).put("slot", slot).put("id", id).put("icon", icon).put("count", count).put("quality", q).put("name", name)
            .put("equip", "").put("stats", JSONArray())
    private fun worn(s: Int, n: String, id: Int?, item: String?, icon: Int?) = JSONObject().put("slot", s).put("name", n)
        .also { o -> if (id != null) o.put("id", id); if (item != null) o.put("item", item); if (icon != null) o.put("icon", icon) }

    /** a small Bags document: the cursor on [cur]'s slot with its tooltip */
    private fun bagsDoc(cur: Int): JSONObject {
        val items = JSONArray()
            .put(item(1, 6948, 134414, "Hearthstone", 1))
            .put(item(2, 1251, 133688, "Linen Bandage", 1, 12))
            .put(item(3, 20744, 134711, "Minor Wizard Oil", 1, 2))
            .put(item(4, 10940, 132871, "Strange Dust", 1, 7))
            .put(item(5, 2589, 132889, "Linen Cloth", 1, 18))
            .put(item(6, 4306, 132903, "Silk Cloth", 1, 4))
            .put(item(7, 118, 134830, "Minor Healing Potion", 1, 3))
            .put(item(8, 15009, 133292, "Grizzly Buckler", 2))
        val tips = mapOf(
            2 to JSONArray().put(l("Linen Bandage")).put(l("Requires First Aid (1)")).put(l("Use: Heals 66 damage over 6 sec.", "00ff00")),
            3 to JSONArray().put(l("Minor Wizard Oil")).put(l("Requires Level 5")).put(l("Use: While applied to target weapon it increases spell damage by up to 8. Lasts for 30 minutes.", "00ff00")))
        val tip = JSONObject().put("key", "bag 0 $cur ${if (cur == 2) 1251 else 20744}").put("lines", tips.getValue(cur))
        val ctl = JSONObject().put("on", true).put("mode", "grid").put("seq", 1).put("sid", 1)
            .put("cur", JSONObject().put("bag", 0).put("slot", cur)).put("tip", tip)
        val equipped = JSONObject().put("slots", JSONArray()
            .put(worn(1, "Head", null, null, null)).put(worn(5, "Chest", 900, "Tattered Cloth Vest", 135009))
            .put(worn(16, "Main Hand", 902, "Worn Shortsword", 135274)).put(worn(17, "Off Hand", 903, "Battered Buckler", 134955)))
        return JSONObject().put("free", 8).put("total", 16).put("money", 102345)
            .put("bags", JSONArray().put(JSONObject().put("bag", 0).put("slots", 16)))
            .put("items", items).put("equipped", equipped).put("ctl", ctl)
            .put("pad", JSONObject().put("rows", JSONObject().put("bmvq", 27).put("bag0", 30)))
    }

    /** [room] above, the rooms' bar under it: the whole bottom screen */
    private fun screen(room: View, s: Strip): Bitmap {
        lay(room, W, H)
        val bm = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) {
            val c = Canvas(bm); c.drawColor(Theme.ink)
            room.draw(c)
            lay(s, W, Strip.HEIGHT)
            c.save(); c.translate(0f, H.toFloat()); s.draw(c); c.restore()
            if (pass == 0) settle()
        }
        return bm
    }
    /** the rooms' bar alone, its tracking band large: a close-up of the band at one moment */
    private fun band(cv: CastView, room: Room): Bitmap {
        val s = strip(cv, room)
        val full = drawStrip(s)
        return Bitmap.createBitmap(full, 0, 0, W, Strip.TRACKS + 8)
    }
    private fun save(b: Bitmap, name: String) {
        val d = out ?: return
        d.mkdirs()
        File(d, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    /** close-ups stacked into one sheet */
    private fun sheet(rows: List<Bitmap>): Bitmap {
        val gap = 10
        val h = rows.sumOf { it.height + gap }
        val bm = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm); c.drawColor(android.graphics.Color.rgb(60, 60, 60))
        var y = 0
        for (r in rows) { c.drawBitmap(r, 0f, y.toFloat(), null); y += r.height + gap }
        return bm
    }

    @Test fun shots() {
        if (out == null) return
        Tr.init(app)
        HomeSettings.init(app)
        val cv = CastView(app) { }
        settle(); settle()                                          // the cast bar's art decodes off the UI thread

        // BAGS: Use on a bandage (First Aid, an 8 s channel), a third in - and how it looked before (no band: nothing on screen)
        val bags = BagsView(app)
        bags.update(JSONObject(bagsDoc(2).toString()).put("ctl", JSONObject().put("on", false)), true)
        bags.update(bagsDoc(2), true)
        casting(cv, "First Aid", 8.0, 0.33, channel = true)
        save(screen(bags, strip(cv, Room.BAGS, band = false)), "bags_bandage_before.png")
        save(screen(bags, strip(cv, Room.BAGS)), "bags_bandage_after.png")
        // BAGS: an enchanting reagent's Disenchant (3 s), most of the way
        val bags2 = BagsView(app)
        bags2.update(JSONObject(bagsDoc(3).toString()).put("ctl", JSONObject().put("on", false)), true)
        bags2.update(bagsDoc(3), true)
        casting(cv, "Disenchant", 3.0, 0.8)
        save(screen(bags2, strip(cv, Room.BAGS)), "bags_disenchant_after.png")

        // CHARACTER: Enchant -> Minor Wizard Oil on the main hand (a 3 s cast), half way
        val ch = CharacterView(app)
        ch.updateBags(bagsDoc(3))
        casting(cv, "Minor Wizard Oil", 3.0, 0.5)
        save(screen(ch, strip(cv, Room.CHARACTER, band = false)), "character_oil_before.png")
        save(screen(ch, strip(cv, Room.CHARACTER)), "character_oil_after.png")

        // SPELLBOOK: a Shaman's Healing Wave from the book (the pet-spells fixture), a quarter in
        val fx = listOf(File("../dsforever/src/test/resources/pet-fixtures"), File("dsforever/src/test/resources/pet-fixtures"))
            .map { File(it, "spells_shaman.json") }.firstOrNull { it.isFile }
        if (fx != null) {
            val book = SpellbookView(app, onBind = { })
            lay(book, W, H)
            book.update(SpellsModel.parse(JSONObject(fx.readText()))!!)
            casting(cv, "Healing Wave", 3.0, 0.25)
            save(screen(book, strip(cv, Room.SPELLBOOK)), "spellbook_cast_after.png")
        }

        // MACROS: a macro's Lightning Bolt (its grid document from the Macros room's own run)
        System.getenv("ITEMCAST_MACRO_DOCS")?.let { File(it, "grid.json") }?.takeIf { it.isFile }?.let { f ->
            val mv = MacrosView(app).apply { dryRun = true }
            mv.onDoc(JSONObject(f.readText()), live = false)
            casting(cv, "Lightning Bolt", 2.5, 0.6)
            save(screen(mv, strip(cv, Room.MACROS)), "macros_cast_after.png")
        }

        // THE BAND, close up: a cast's fill, a channel's drain, an uninterruptible cast, a long name, the finish, the interrupt, gone
        val rows = ArrayList<Bitmap>()
        ended(cv, ok = true); later(FINISH_MS + 10)
        rows += band(cv, Room.BAGS)                                // nothing cast: the experience bar as it was
        for (p in listOf(0.1, 0.5, 0.9)) { casting(cv, "Disenchant", 3.0, p); rows += band(cv, Room.BAGS) }
        casting(cv, "First Aid", 8.0, 0.3, channel = true); rows += band(cv, Room.BAGS)
        casting(cv, "Hearthstone", 10.0, 0.5, ni = true); rows += band(cv, Room.BAGS)
        casting(cv, "Enchant Bracer - Lesser Intellect, the very long name", 5.0, 0.4); rows += band(cv, Room.BAGS)
        casting(cv, "Disenchant", 3.0, 0.98); later(80); ended(cv, ok = true); later(120); rows += band(cv, Room.BAGS)
        later(260); rows += band(cv, Room.BAGS)
        casting(cv, "Disenchant", 3.0, 0.5); ended(cv, ok = false); later(200); rows += band(cv, Room.BAGS)
        later(FAIL_MS); rows += band(cv, Room.BAGS)
        save(sheet(rows), "band_sheet.png")
    }
}
