package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * #471 (a player on 1.0.33: "I cannot see which rank my spells are when assigning them to my crossbars"): the rank badge drawn by
 * Robolectric's native graphics on Home's buttons and its tap-to-cast bar, the Spellbook room, a spell wheel and the Controller room's
 * chooser, from documents shaped as the add-on sends them (the `spells` document's `sub`). The checks always run; the PNGs (each
 * surface BEFORE = no rank index, as 1.0.37 draws it, and AFTER) only when RANK_SHOTS_DIR is set (RANK_ART_ROOT = an art root laid out
 * like the app's: ui/kit, fonts, icons.pak).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RankBadgeShotsTest {
    private val out: File? = System.getenv("RANK_SHOTS_DIR")?.let { File(it).also { d -> d.mkdirs() } }
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        HomeSettings.init(app); HomeSettings.rankBadges = true          // 2026-11-20: the badges are a setting, Off by default
        System.getenv("RANK_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
    }
    @After fun down() {
        SpellRanks.update(null)
        CastStripModel.Live.pos = 0f; CastStripModel.Live.side = null
        HomeSettings.init(app); HomeSettings.castBarSide = CastStripModel.Side.OFF; HomeSettings.rankBadges = false
    }

    // ---------------------------------------------------------------- a level 20 Shaman's book (Spells.lua readItem's fields)

    private data class S(val id: Int, val name: String, val sub: String?, val icon: Int, val low: Boolean = false, val pas: Boolean = false)
    private val spells = listOf(
        S(403, "Lightning Bolt", "Rank 1", 136048, low = true), S(529, "Lightning Bolt", "Rank 2", 136048, low = true),
        S(548, "Lightning Bolt", "Rank 3", 136048, low = true), S(915, "Lightning Bolt", "Rank 4", 136048),
        S(331, "Healing Wave", "Rank 1", 136052, low = true), S(332, "Healing Wave", "Rank 2", 136052, low = true),
        S(547, "Healing Wave", "Rank 3", 136052),
        S(8042, "Earth Shock", "Rank 1", 136026, low = true), S(8044, "Earth Shock", "Rank 2", 136026),
        S(324, "Lightning Shield", "Rank 1", 136051),
        S(8017, "Rockbiter Weapon", "Rank 1", 136086, low = true), S(8018, "Rockbiter Weapon", "Rank 2", 136086),
        S(2645, "Ghost Wolf", null, 136095),
        S(16176, "Ancestral Healing", "Rank 1", 136109, pas = true))

    private fun bookDoc(allRanks: Boolean): JSONObject {
        val items = JSONArray()
        spells.forEachIndexed { i, s ->
            val o = JSONObject().put("sl", i + 1).put("t", 1).put("a", s.id).put("id", s.id).put("n", s.name).put("i", s.icon).put("ln", 2)
                .put("pv", i).put("ct", 1500).put("bars", JSONArray().put(i + 1))
            if (s.sub != null) o.put("sub", s.sub)
            if (s.low) o.put("low", true)
            if (s.pas) o.put("pas", true)
            items.put(o)
        }
        return JSONObject().put("type", "spells").put("v", "s17").put("s", JSONObject().put("lvl", 20).put("ranks", allRanks)
            .put("lines", JSONArray().put(JSONObject().put("k", 2).put("n", "Elemental Combat").put("i", 136048)))
            .put("items", items))
    }
    private fun book(allRanks: Boolean = true) = SpellsModel.parse(bookDoc(allRanks))!!

    // ---------------------------------------------------------------- the surfaces

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    private fun settle() { ShadowLooper.idleMainLooper(); Thread.sleep(150); ShadowLooper.idleMainLooper() }
    private fun draw(v: View, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) { val c = Canvas(b); c.drawColor(Theme.ink); v.draw(c); if (pass == 0) settle() }
        return b
    }
    private fun save(b: Bitmap, name: String) {
        val d = out ?: return
        File(d, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Home's base set: D-pad Left Lightning Shield (a lone rank), Up Ghost Wolf (no rank), Right Rockbiter R1 (lower), X Lightning
     *  Bolt R3 (lower: R4 known), Y Healing Wave R3 (the top), A Earth Shock R2 (the top); B the game's Back */
    private fun hud(): JSONObject {
        val n = JSONArray()
        fun sp(p: Int, id: Int, icon: Int) = JSONObject().put("p", p).put("s", p).put("k", "spell").put("id", id).put("i", icon)
        n.put(sp(1, 324, 136051)).put(sp(2, 2645, 136095)).put(sp(3, 8017, 136086)).put(JSONObject().put("p", 4))
            .put(sp(5, 548, 136048)).put(sp(6, 547, 136052)).put(JSONObject().put("p", 7).put("k", "fixed").put("fx", "back"))
            .put(sp(8, 8044, 136026))
        return JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", n)))
    }
    private val castDoc = JSONObject("""{"type":"castbar","v":"cb6","n":8,"pick":8,"pp":[39,19],"e":[
        {"s":1,"id":915,"i":136048,"c":0},{"s":2,"id":529,"i":136048,"c":1},{"s":3,"id":547,"i":136052,"c":2},
        {"s":4,"id":324,"i":136051,"c":3},{"s":5,"id":8042,"i":136026,"c":4},{"s":6,"id":2645,"i":136095,"c":5}]}""")

    private fun home(): HomeView {
        HomeSettings.init(app)
        HomeSettings.castBarSide = CastStripModel.Side.LEFT
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        lay(home, 1240, 1080 - Strip.HEIGHT)
        cast.update(hud())
        val bar = CastStripModel.parse(castDoc)!!
        CastStripModel.Live.set(bar)
        home.onCastBar(bar)
        home.tick()
        ShadowLooper.idleMainLooper()
        lay(home, 1240, 1080 - Strip.HEIGHT)
        return home
    }
    private fun homeShot(): Bitmap { val h = home(); return draw(h, 1240, 1080 - Strip.HEIGHT) }

    private fun bookShot(b: SpellsModel.Book): Bitmap {
        val v = SpellbookView(app, onBind = { })
        lay(v, 1240, 960)
        v.update(b)
        return draw(v, 1240, 960)
    }

    /** the Controller room, A's chooser open on the Spells tab, the controller on Lightning Bolt Rank 3 */
    private fun chooserShot(b: SpellsModel.Book): Bitmap {
        val v = ControllerView(app, { true })
        v.update(b)
        val slots = JSONArray()
        fun sl(pos: Int, id: Int, n: String, i: Int) = JSONObject().put("pos", pos).put("s", pos).put("kind", "spell").put("id", id).put("n", n).put("i", i)
        slots.put(sl(1, 324, "Lightning Shield", 136051)).put(sl(2, 2645, "Ghost Wolf", 136095)).put(sl(3, 8017, "Rockbiter Weapon", 136086))
            .put(JSONObject().put("pos", 4).put("kind", "none"))
            .put(sl(5, 548, "Lightning Bolt", 136048)).put(sl(6, 547, "Healing Wave", 136052))
            .put(JSONObject().put("pos", 7).put("kind", "fixed").put("n", "Back")).put(sl(8, 8044, "Earth Shock", 136026))
        v.onPadMap(PadMapModel.parse(JSONObject().put("type", "padmap").put("v", "m174").put("m", JSONObject().put("page", 1)
            .put("sets", JSONArray().put(JSONObject().put("set", 1).put("slots", slots)))))!!)
        v.onPadTabCtl(PadMapModel.parseCtl(JSONObject().put("type", "padtabctl").put("c", JSONObject().put("on", true).put("set", 1)
            .put("pos", 8).put("chooser", JSONObject().put("list", "spells").put("row", 3).put("id", "548"))))!!)
        lay(v, 1240, 960)
        return draw(v, 1240, 960)
    }

    /** a spell wheel at rest (the Controller room's editor and Home's wheel draw the same slices) */
    private fun wheelShot(): Bitmap {
        val b = Bitmap.createBitmap(760, 760, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Theme.ink)
        val items = listOf(915, 548, 547, 8044, 324, 2645).map { id ->
            val s = spells.first { it.id == id }
            UiKit.RadialItem(Icons.get(s.icon), s.name, -1, rank = SpellRanks.of(id), cdLeft = if (id == 547) 4f else 0f, cdLen = if (id == 547) 6f else 0f)
        }
        UiKit.radialPreview(c, 380f, 380f, 720f, items, 1, even = items.size)
        return b
    }

    private fun same(a: Bitmap, b: Bitmap): Boolean = a.width == b.width && a.height == b.height &&
        IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
            .contentEquals(IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) })

    // ---------------------------------------------------------------- checks (always)

    @Test fun theBadgeSitsClearOfTheGlyphTheCountAndTheDsMark() {
        val c = Canvas(Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888))
        for (k in listOf(1.6f, 2.4f, 3.2f)) for (circle in listOf(true, false)) {
            // CastView.drawSlot: the icon's half (19 / 20 units less 3), the glyph's middle (+11.5, -8.5 / -11.5) and its 7.5 unit disc
            val half = (if (circle) 19f else 20f) * k; val ih = half - 3f * k
            val cx = 100f; val cy = 100f
            val chip = UiKit.spellRank(c, RectF(cx - ih, cy - ih, cx + ih, cy + ih), SpellRanks.Badge(11, true))
            val glyphLeft = cx + 11.5f * k - 7.5f * k
            assertTrue("k=$k circle=$circle: clear of the glyph (${chip.right} < $glyphLeft)", chip.right < glyphLeft)
            assertTrue("k=$k circle=$circle: the top half only (the count and the DS mark are below)", chip.bottom < cy)
        }
    }

    @Test fun onAWheelTheBadgeIsBottomLeftUnderTheSeconds() {
        val c = Canvas(Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888))
        // crWheel's icon: 75.4 x k, the seconds 30 x k round its middle; the pointed slice's A glyph is top left, a pin top right
        val k = 720f / 540f; val sz = 75.4f * k
        val r = RectF(200f - sz / 2f, 200f - sz / 2f, 200f + sz / 2f, 200f + sz / 2f)
        val chip = UiKit.spellRank(c, r, SpellRanks.Badge(4, true), UiKit.RANK_BOTTOM_LEFT)
        assertTrue("under the seconds", chip.top > r.centerY() + 15f * k * 0.75f)
        assertTrue("left half (a count is bottom right)", chip.right < r.centerX())
    }

    @Test fun onlyRankedSpellsWearABadgeAndALowerOneIsGrey() {
        SpellRanks.update(book())
        assertEquals(SpellRanks.Badge(3, false), SpellRanks.of(548))       // X: Rank 3 with Rank 4 known
        assertEquals(SpellRanks.Badge(4, true), SpellRanks.of(915))
        assertEquals(SpellRanks.Badge(2, true), SpellRanks.of(8044))
        assertEquals(null, SpellRanks.of(324))                              // a lone rank
        assertEquals(null, SpellRanks.of(2645))                             // no rank
        assertEquals(null, SpellRanks.of(16176))                            // a passive
    }

    @Test fun homeTheBookTheChooserAndTheWheelDrawTheBadge() {
        val b = book()
        SpellRanks.update(null)
        val homeBefore = homeShot(); val bookBefore = bookShot(b); val chooserBefore = chooserShot(b); val wheelBefore = wheelShot()
        SpellRanks.update(b)
        val homeAfter = homeShot(); val bookAfter = bookShot(b); val chooserAfter = chooserShot(b); val wheelAfter = wheelShot()
        save(homeBefore, "home_before.png"); save(homeAfter, "home_after.png")
        save(bookBefore, "spellbook_before.png"); save(bookAfter, "spellbook_after.png")
        save(chooserBefore, "controller_chooser_before.png"); save(chooserAfter, "controller_chooser_after.png")
        save(wheelBefore, "spell_wheel_before.png"); save(wheelAfter, "spell_wheel_after.png")
        assertFalse("Home shows badges", same(homeBefore, homeAfter))
        assertFalse("the Spellbook shows badges", same(bookBefore, bookAfter))
        assertFalse("the chooser shows badges", same(chooserBefore, chooserAfter))
        assertFalse("the wheel shows badges", same(wheelBefore, wheelAfter))
    }

    /** the user, 2026-11-20 ("put that as a toggle in the settings, off by default"): Settings > Bottom screen > Spell rank badges Off =
     *  Home (its buttons and the tap-to-cast bar), the Spellbook, the chooser and a wheel drawn exactly as with no rank index at all;
     *  On = the badges again, the same book */
    @Test fun theSettingOffHidesEveryBadge() {
        val b = book()
        SpellRanks.update(null)
        // in the full suite an earlier class leaves the art root set and the controller kit loads off the main thread while this test
        // draws (the first Home came out in the fallback frames, the next in the kit's): settle the art before the reference shots
        var prev = homeShot(); bookShot(b); chooserShot(b)
        for (i in 0 until 40) { settle(); val next = homeShot(); if (same(prev, next)) break; prev = next }
        val homeNone = homeShot(); val bookNone = bookShot(b); val chooserNone = chooserShot(b); val wheelNone = wheelShot()
        SpellRanks.update(b)
        HomeSettings.rankBadges = false
        val homeOff = homeShot(); val bookOff = bookShot(b); val chooserOff = chooserShot(b); val wheelOff = wheelShot()
        save(homeOff, "home_setting_off.png"); save(bookOff, "spellbook_setting_off.png")
        assertTrue("Home: no badge", same(homeNone, homeOff))
        assertTrue("the Spellbook: no badge", same(bookNone, bookOff))
        assertTrue("the chooser: no badge", same(chooserNone, chooserOff))
        assertTrue("the wheel: no badge", same(wheelNone, wheelOff))
        HomeSettings.rankBadges = true
        assertFalse("On: Home's badges back", same(homeOff, homeShot()))
        assertFalse("On: the Spellbook's badges back", same(bookOff, bookShot(b)))
    }

    @Test fun aBookWithNoFamiliesChangesNothing() {
        // the same Home with a book of lone ranks only: drawn exactly as before (no badge anywhere)
        SpellRanks.update(null)
        val before = homeShot()
        val lone = JSONObject(bookDoc(true).toString())
        val items = lone.getJSONObject("s").getJSONArray("items")
        val keep = JSONArray()
        for (i in 0 until items.length()) items.getJSONObject(i).let { o -> if (o.optInt("id") in setOf(915, 547, 8044, 324, 8018, 2645)) keep.put(o) }
        lone.getJSONObject("s").put("items", keep)
        SpellRanks.update(SpellsModel.parse(lone)!!)
        assertTrue(same(before, homeShot()))
    }
}
