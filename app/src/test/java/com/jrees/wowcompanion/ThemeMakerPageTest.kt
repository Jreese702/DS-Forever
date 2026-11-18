package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * THE THEME MAKER AND THE PREMADE THEMES, on the page (beautify 9; the user, 2026-11-17: "lets add a custom theme maker where people can
 * choose their pallets with an oled toggle, and then have an extra button to show the premade themes"): the landing, the pad's walk
 * (Up from Accent = Premade themes, Down from the last row = Use, the Shade row gone while OLED is on, no dead stops), Use as the ONE
 * write, the gallery applying at once without touching the custom slot, B one step back. The renders (maker, gallery, OLED draft, the
 * guard's mark, the live mini Home, the no-kit drawings, the Settings / Controller grounds, pick 9's three OLED art levels) are written
 * when MAKER_SHOTS_DIR is set (MAKER_ART_ROOT = an art root; MAKER_MACRO_DOCS / MAKER_SOCIAL = the Macros / Social documents).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeMakerPageTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun prefs() = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Before fun fresh() {
        prefs().edit().clear().commit()
        Theme.init(app, reread = true)
        Theme.use(ThemePresets.CLASSIC.id)
        ThemeMakerPage.forget()
        ThemeMakerPage.homeRender = null
    }

    @After fun back() {
        Theme.use(ThemePresets.CLASSIC.id)
        prefs().edit().clear().commit()
        Theme.init(app, reread = true)
        ThemeMakerPage.forget()
        ThemeMakerPage.homeRender = null
    }

    private fun page(): ThemeMakerPage = ThemeMakerPage().also {
        it.open()
        it.draw(Canvas(Bitmap.createBitmap(1240, 760, Bitmap.Config.ARGB_8888)), 1240f, 760f)
    }

    @Test fun landsOnThePickedAccent() {
        Theme.use("oled_rose")
        val p = page()
        assertFalse(p.gallery)
        assertEquals(ThemeMakerPage.Stop.Pick(ThemeMakerPage.Row.ACCENT, 3), p.cursor)          // Rose
        assertTrue(p.draft.oled)
        // the back chip is never a pad stop
        assertTrue(p.stops().none { s -> p.stopRect(s)?.let { RectF.intersects(it, p.backChip) } == true })
    }

    @Test fun padWalksRowsAndButtons() {
        val p = page()
        assertEquals(ThemeMakerPage.Stop.Pick(ThemeMakerPage.Row.ACCENT, 0), p.cursor)
        assertFalse(p.move(-1, 0))                                                                // nothing left of the first swatch
        assertTrue(p.move(0, -1)); assertEquals(ThemeMakerPage.Stop.Premade, p.cursor)          // Up from Accent
        assertTrue(p.move(0, 1))
        val under = p.cursor as ThemeMakerPage.Stop.Pick
        assertEquals(ThemeMakerPage.Row.ACCENT, under.row)
        assertTrue("the swatch under the button (right side): ${under.i}", under.i >= 6)
        // down every row to Use
        var steps = 0
        while (p.cursor != ThemeMakerPage.Stop.Use && steps < 12) { assertTrue(p.move(0, 1)); steps++ }
        assertEquals(ThemeMakerPage.Stop.Use, p.cursor)
        assertEquals(7, steps)                                                                    // Accent .. Art (7 rows) then Use
        assertFalse(p.move(0, 1))
        assertTrue(p.move(0, -1)); assertEquals(ThemeMakerPage.Row.ART, (p.cursor as ThemeMakerPage.Stop.Pick).row)
    }

    @Test fun oledHidesTheShadeRow() {
        val p = page()
        assertTrue(ThemeMakerPage.Row.SHADE in p.rows())
        // to the OLED row, On
        while ((p.cursor as? ThemeMakerPage.Stop.Pick)?.row != ThemeMakerPage.Row.OLED) p.move(0, 1)
        while ((p.cursor as ThemeMakerPage.Stop.Pick).i != 0) p.move(-1, 0)
        assertTrue(p.press())
        assertTrue(p.draft.oled)
        assertFalse(ThemeMakerPage.Row.SHADE in p.rows())
        assertTrue(p.stops().none { it is ThemeMakerPage.Stop.Pick && it.row == ThemeMakerPage.Row.SHADE })
        assertEquals(ThemeMakerPage.Stop.Pick(ThemeMakerPage.Row.OLED, 0), p.cursor)            // the cursor stays on what was pressed
        assertNotNull(p.cursorRect())
        // Up from OLED skips the hidden row: Text
        p.move(0, -1); assertEquals(ThemeMakerPage.Row.TEXT, (p.cursor as ThemeMakerPage.Stop.Pick).row)
        // nothing applied yet: the theme in use is untouched until Use
        assertSame(ThemePresets.CLASSIC, Theme.cur)
    }

    @Test fun useIsTheOneWrite() {
        val p = page()
        p.move(1, 0); p.move(1, 0); p.move(1, 0)                                                   // Accent: Rose
        p.press()
        assertSame(ThemePresets.CLASSIC, Theme.cur)
        assertNull(prefs().getString("theme_custom", null))
        while (p.cursor != ThemeMakerPage.Stop.Use) p.move(0, 1)
        assertTrue(p.press())
        assertEquals("custom", Theme.cur.id)
        assertEquals(0xFF8CC6, Theme.cur.picks!!.accent and 0xFFFFFF)
        assertEquals("custom", prefs().getString("theme", null))
        val saved = ThemeMaker.decode(prefs().getString("theme_custom", null))
        assertEquals(p.draft, saved)
        assertSame(Theme.custom, Theme.all.last())
        assertEquals(ThemePresets.ALL.size, Theme.presets.size)                                 // the premade list never grows
        // the next start reads it back
        Theme.use("classic")
        prefs().edit().putString("theme", "custom").commit()
        Theme.init(app, reread = true)
        assertEquals("custom", Theme.cur.id)
        assertEquals(saved, Theme.cur.picks)
    }

    @Test fun galleryAppliesAtOnceAndKeepsTheCustomSlot() {
        val p = page()
        while (p.cursor != ThemeMakerPage.Stop.Use) p.move(0, 1)
        p.press()                                                                                  // a custom theme saved (Classic's picks)
        val slot = prefs().getString("theme_custom", null)
        p.move(0, -1)
        while (p.cursor != ThemeMakerPage.Stop.Premade) p.move(0, -1)
        p.press()
        assertTrue(p.gallery)
        assertEquals(ThemeMakerPage.Stop.Tile(Theme.all.indexOf(Theme.cur)), p.cursor)        // lands on the theme in use (the custom)
        // to OLED Frost (index 6) and A
        while ((p.cursor as ThemeMakerPage.Stop.Tile).i != 6) p.move(if ((p.cursor as ThemeMakerPage.Stop.Tile).i > 6) -1 else 1, 0)
        p.press()
        assertSame(ThemePresets.OLED_FROST, Theme.cur)
        assertEquals(ThemeMakerPage.Stop.Tile(6), p.cursor)                                        // the cursor stays
        assertEquals(slot, prefs().getString("theme_custom", null))                                // never the custom slot
        // the maker now starts from the premade picked
        assertTrue(p.draft.oled); assertEquals(0x96D4FF, p.draft.accent and 0xFFFFFF)
        // B: the gallery back to the maker, on its button; B again is the host's (back to the tile)
        assertTrue(p.back()); assertFalse(p.gallery); assertEquals(ThemeMakerPage.Stop.Premade, p.cursor)
        assertFalse(p.back())
    }

    @Test fun draftLivesForTheSession() {
        val p = page()
        p.move(1, 0); p.press()                                                                    // Pale gold, not saved
        val draft = p.draft
        val q = page()                                                                             // left with B, opened again
        assertEquals(draft, q.draft)
        Theme.use("dark_rose")                                                                     // the theme in use changed: start from it
        val r = page()
        assertEquals(ThemeMaker.startOf(ThemePresets.DARK_ROSE), r.draft)
    }

    @Test fun touchIsThePad() {
        val p = page()
        var closed = 0
        p.onClose = { closed++ }
        val b = p.stopRect(ThemeMakerPage.Stop.Pick(ThemeMakerPage.Row.METAL, 6))!!
        assertTrue(p.tap(b.centerX(), b.centerY()))
        assertEquals("jade", p.draft.metal)
        assertEquals(ThemeMakerPage.Stop.Pick(ThemeMakerPage.Row.METAL, 6), p.cursor)
        assertTrue(p.tap(p.backChip.centerX(), p.backChip.centerY()))
        assertEquals(1, closed)
    }

    @Test fun previewDrawsAnotherThemeAndComesBack() {
        var heard = 0
        val l: () -> Unit = { heard++ }
        Theme.onChange(l)
        val seen = Theme.preview(ThemePresets.OLED_JADE) { Theme.gold }
        Theme.removeListener(l)
        assertEquals(ThemePresets.OLED_JADE.accent, seen)
        assertSame(ThemePresets.CLASSIC, Theme.cur)
        assertEquals(0, heard)
        assertEquals("classic", prefs().getString("theme", "classic"))
    }

    // ---------------------------------------------------------------- the renders

    private val out: File? = System.getenv("MAKER_SHOTS_DIR")?.let { File(it) }

    private fun artRoot(root: String?) {
        val f = ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }
        f.set(null, File(root ?: "/nonexistent"))
        UiKit.root = File(root ?: "/nonexistent")                                                  // drops the kit's caches
        ThemeGallery.forget()
    }

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun save(b: Bitmap, name: String) { out!!.mkdirs(); File(out, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }

    /** the Settings room as the page will stand: the tab bar, the room's ground, the maker in the tab body, the bar */
    private fun room(p: ThemeMakerPage, before: (ThemeMakerPage) -> Unit = {}): Bitmap {
        val bar = SettingsTabBar(app) { }.apply { tab = SettingsTabs.Tab.GENERAL }
        lay(bar, 1240, 200)
        val barH = bar.measuredHeight
        val bodyH = 1080 - Strip.HEIGHT - barH
        val b = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        val strip = Strip(app) { }.apply { active = Room.SETTINGS }
        p.open(); before(p)
        for (pass in 0..1) {
            val c = Canvas(b)
            c.drawColor(Theme.ink)
            lay(bar, 1240, barH); bar.draw(c)
            c.save(); c.translate(0f, barH.toFloat())
            RoomGround.settings(c, RectF(0f, 0f, 1240f, bodyH.toFloat()))
            p.draw(c, 1240f, bodyH.toFloat())
            c.restore()
            lay(strip, 1240, Strip.HEIGHT)
            c.save(); c.translate(0f, (1080 - Strip.HEIGHT).toFloat()); strip.draw(c); c.restore()
            if (pass == 0) { ShadowLooper.idleMainLooper(); Thread.sleep(150); ShadowLooper.idleMainLooper() }
        }
        return b
    }

    /** a real Home drawn in [spec] (Theme.preview) into [into]: what core's PreviewStage.renderWith gives the maker */
    private fun liveHome(): (ThemeSpec, Bitmap) -> Boolean {
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        val h = 1080 - Strip.HEIGHT
        lay(home, 1240, h)
        // sample documents only (the ToS ruling): a zone, the bars' page, two tracked quests
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", org.json.JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", org.json.JSONArray()))))
        home.onFull(JSONObject().put("type", "full").put("map", JSONObject().put("zone", "Elwynn Forest").put("pvp", "contested"))
            .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true))), true)
        val q = JSONObject().put("plv", 6).put("list", org.json.JSONArray()
            .put(JSONObject().put("id", 7).put("title", "Kobold Camp Cleanup").put("lv", 6).put("watch", 1).put("focus", true).put("zone", "Elwynn Forest")
                .put("obj", org.json.JSONArray().put(JSONObject().put("t", "Kobold Vermin slain: 4/10"))))
            .put(JSONObject().put("id", 33).put("title", "Wolves Across the Border").put("lv", 6).put("watch", 1).put("zone", "Elwynn Forest")
                .put("obj", org.json.JSONArray().put(JSONObject().put("t", "Tough Wolf Meat: 2/8")))))
        map.onQuests(q); home.onQuests(q, true)
        home.tick(); ShadowLooper.idleMainLooper()
        return { spec, into ->
            Theme.preview(spec) {
                lay(home, 1240, h); home.tick()
                val look = HomeView.Companion.Look(HomeRest.DIAMONDS, false, false, false)
                HomeView::class.java.methods.firstOrNull { it.name.startsWith("previewSettle") }?.invoke(home, look)
                lay(home, 1240, h)
                val c = Canvas(into)
                c.drawColor(Theme.ink)
                // the top of Home (map + controller), scaled to the box's width
                val k = into.width / 1240f
                c.save(); c.scale(k, k); home.draw(c); c.restore()
            }
            true
        }
    }

    @Test fun shots() {
        if (out == null) return
        val root = System.getenv("MAKER_ART_ROOT")
        artRoot(root); Tr.init(app); Settings.init(app); SettingsTabs.init(app)
        // the maker in Classic, landing (the picked Accent under the cursor)
        save(room(ThemeMakerPage()), "maker_classic.png")
        // an OLED draft: Jade accent, Jade metal, Moss ground, OLED on (the Shade row gone), the cursor on OLED
        save(room(ThemeMakerPage()) { p ->
            fun pick(r: ThemeMakerPage.Row, i: Int) { val s = p.stopRect(ThemeMakerPage.Stop.Pick(r, i))!!; p.tap(s.centerX(), s.centerY()) }
            pick(ThemeMakerPage.Row.ACCENT, 6); pick(ThemeMakerPage.Row.METAL, 6); pick(ThemeMakerPage.Row.GROUND, 4); pick(ThemeMakerPage.Row.OLED, 0)
        }, "maker_oled_draft.png")
        // the guard's mark: a silver-white accent over paper words (the words stepped down), OLED Silver in use
        Theme.use("oled_silver"); ThemeMakerPage.forget()
        save(room(ThemeMakerPage()) { p ->
            val s = p.stopRect(ThemeMakerPage.Stop.Pick(ThemeMakerPage.Row.TEXT, 1))!!; p.tap(s.centerX(), s.centerY())
        }, "maker_guard_mark.png")
        // the gallery: Classic in use, then OLED Rose in use (its tick), the cursor on it
        Theme.use("classic"); ThemeMakerPage.forget()
        save(room(ThemeMakerPage()) { p -> p.move(0, -1); p.press() }, "gallery_classic.png")
        Theme.use("oled_rose"); ThemeMakerPage.forget()
        save(room(ThemeMakerPage()) { p -> p.move(0, -1); p.press() }, "gallery_oled_rose.png")
        // a saved custom theme in the gallery (last), in use
        Theme.use("classic"); ThemeMakerPage.forget()
        Theme.saveCustom(ThemeMaker.Picks(0xFFC7A0FF.toInt(), "violet", 265f, 0.25f, 0.045f, 0xFFF0E6EB.toInt(), true, ThemeMaker.Art.HIGH))
        save(room(ThemeMakerPage()) { p -> p.move(0, -1); p.press() }, "gallery_custom.png")
        save(room(ThemeMakerPage()), "maker_custom_in_use.png")
        Theme.use("classic"); ThemeMakerPage.forget()
        // the live mini Home (the integrator wires PreviewStage.renderWith; here a real Home through Theme.preview)
        ThemeMakerPage.homeRender = liveHome()
        save(room(ThemeMakerPage()) { p ->
            fun pick(r: ThemeMakerPage.Row, i: Int) { val s = p.stopRect(ThemeMakerPage.Stop.Pick(r, i))!!; p.tap(s.centerX(), s.centerY()) }
            pick(ThemeMakerPage.Row.ACCENT, 5); pick(ThemeMakerPage.Row.METAL, 5); pick(ThemeMakerPage.Row.GROUND, 2); pick(ThemeMakerPage.Row.OLED, 0)
        }, "maker_live_home.png")
        ThemeMakerPage.homeRender = null; ThemeMakerPage.forget()
        // the Settings and Controller grounds per theme (core wires the calls; here the ground alone, a tile row's plates over it)
        for (id in listOf("oled_bronze", "oled_rose", "dark_silver", "classic")) {
            Theme.use(id)
            for ((n, f) in listOf<Pair<String, (Canvas, RectF) -> Unit>>("settings" to RoomGround::settings, "controller" to RoomGround::controller)) {
                val b = Bitmap.createBitmap(1240, 600, Bitmap.Config.ARGB_8888)
                val c = Canvas(b)
                f(c, RectF(0f, 0f, 1240f, 600f))
                val pf = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                for (i in 0 until 5) {
                    val r = ThemeGallery.tile(i, 1240f, 40f)
                    pf.color = Theme.raised; c.drawRoundRect(r, 10f, 10f, pf)
                    ThemeGallery.edge(c, r, false)
                }
                // the controller's cursor as the app draws it: the golden box (UiKit.focus), never a gold outline
                ThemeGallery.tile(0, 1240f, 40f).let { r -> UiKit.focus(c, RectF(r.left - 4f, r.top - 4f, r.right + 4f, r.bottom + 4f)) }
                save(b, "ground_${n}_$id.png")
            }
        }
        Theme.use("classic")
        // pick 9: OLED Bronze's art levels side by side
        pick9("macros") { MacrosView(app).apply { dryRun = true }.also { v ->
            System.getenv("MAKER_MACRO_DOCS")?.let { File(it, "grid.json") }?.takeIf { it.isFile }?.let { v.onDoc(JSONObject(it.readText()), live = false) } } }
        pick9("social") { SocialView(app).also { v ->
            System.getenv("MAKER_SOCIAL")?.let { File(it, "social_03_friends.json") }?.takeIf { it.isFile }?.let { v.onDoc(JSONObject(it.readText())) } } }
        // the no-kit drawings (every picture has one): the maker and the gallery without the art
        artRoot(null)
        save(room(ThemeMakerPage()), "maker_nokit.png")
        save(room(ThemeMakerPage()) { p -> p.move(0, -1); p.press() }, "gallery_nokit.png")
        artRoot(root)
    }

    private fun pick9(name: String, make: () -> View) {
        // the design's three (0.22 / 0.30, 0.30 / 0.40 = the user's "Medium" pick, 0.40 / 0.50) and the maker's Low / Mid / High
        val levels = listOf(0.22f to 0.30f, 0.30f to 0.40f, 0.40f to 0.50f, 0.45f to 0.45f, 0.55f to 0.60f, 0.70f to 0.75f)
        val h = 1080 - Strip.HEIGHT
        val shots = levels.map { (s, pn) ->
            Theme.preview(ThemePresets.OLED_BRONZE.copy(stoneLight = s, paneLight = pn)) {
                val v = make()
                val b = Bitmap.createBitmap(1240, h, Bitmap.Config.ARGB_8888)
                for (pass in 0..1) {
                    lay(v, 1240, h)
                    val c = Canvas(b); c.drawColor(Theme.ink); v.draw(c)
                    if (pass == 0) { ShadowLooper.idleMainLooper(); Thread.sleep(150); ShadowLooper.idleMainLooper() }
                }
                b
            }
        }
        for ((i, b) in shots.withIndex()) save(b, "pick9_${name}_${i + 1}.png")
        // side by side at half size, each labelled with its levels
        val sheet = Bitmap.createBitmap(levels.size * 620 + (levels.size + 1) * 12, h / 2 + 60, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet); c.drawColor(android.graphics.Color.rgb(40, 40, 40))
        val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 26f }
        for ((i, b) in shots.withIndex()) {
            val x = 12 + i * (620 + 12)
            c.drawBitmap(b, null, android.graphics.Rect(x, 12, x + 620, 12 + h / 2), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            val inUse = levels[i].first == ThemePresets.OLED_BRONZE.stoneLight && levels[i].second == ThemePresets.OLED_BRONZE.paneLight
            c.drawText("stone ${levels[i].first} / pane ${levels[i].second}" + if (inUse) " (OLED presets)" else "", x.toFloat(), (h / 2 + 46).toFloat(), tp)
        }
        save(sheet, "pick9_${name}_sheet.png")
    }
}
