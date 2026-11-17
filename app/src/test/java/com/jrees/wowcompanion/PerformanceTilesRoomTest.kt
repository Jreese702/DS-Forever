package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * SETTINGS > PERFORMANCE AS PICTURE TILES, off-device (the user, 2026-11-17: "I REALLY REALLY love what you did with the top screen
 * settings menu with the pictures and want to do something similar to the other menus"; the beautify design's section 6): the room
 * built as the host builds it, the tab ONE headless row of tiles (no heading, no fold, no "What it does" panel), the controller's walk
 * (A opens a band and the cursor stays on the tile, Down into its lines, B back on the tile, then the tab row, then Home), touch the
 * same, every press the old rows' own write.
 *
 * With PERF_SHOTS_DIR set it also renders the tab (perf_*: the bands, the cursor, es / fr, no kit) and a sheet of the pictures;
 * THEME_ART_ROOT = an art root laid out like the app's (ui/kit, fonts, minimap); PERF_I18N = the area's es / fr words (merged over the
 * app's for the renders only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PerformanceTilesRoomTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var n = 0
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT
    private var running = false
    private var chose = 0
    private var restarted = 0

    private fun art(root: String? = System.getenv("THEME_ART_ROOT")) {
        val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, root?.let { File(it) })
        UiKit.root = UiKit.root                                         // the kit's caches forget (a font, a texture missing before)
        for (n in listOf("castCache", "castMissing")) when (val o = UiKit::class.java.getDeclaredField(n).apply { isAccessible = true }.get(UiKit)) {
            is MutableMap<*, *> -> o.clear(); is MutableSet<*> -> o.clear()
        }
        MinimapTiles.refresh()
    }

    private fun room(kit: Boolean = true): SettingsRoom {
        Settings.init(app); SettingsTabs.init(app); FrameGen.init(app)
        if (kit) art() else art(null)
        val v = SettingsView(app) { }
        v.gameRunning = { running }
        v.onChooseDll = { chose++ }
        v.onRestart = { restarted++ }
        return SettingsRoom(app, v) { null }.also { it.show(SettingsTabs.Tab.PERFORMANCE); lay(it) }
    }

    private fun lay(r: View) {
        for (k in 0..2) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            r.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
            r.layout(0, 0, W, H)
            r.draw(Canvas(scratch))
        }
    }
    /** one page bitmap for every layout pass (a new one each pass ran the renders out of memory) */
    private val scratch by lazy { Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888) }

    private fun press(r: SettingsRoom, k: String) { r.pad.onPad(JSONObject().put("k", k).put("n", ++n)); lay(r) }

    private fun hold(r: SettingsRoom) {
        r.pad.enter(); r.show(SettingsTabs.Tab.PERFORMANCE, force = true)
        r.pad.onGuestAck(JSONObject().put("op", "hold").put("ok", true)); lay(r)
    }

    private fun contentTop(r: SettingsRoom, v: View): Float {
        val sc = SettingsRoom::class.java.getDeclaredField("scroll").apply { isAccessible = true }.get(r) as android.widget.ScrollView
        val content = sc.getChildAt(0)
        var y = 0f; var cur: View = v
        while (cur !== content) { y += cur.top; cur = cur.parent as View }
        return y
    }

    /** the tiles' boxes in the page's content coordinates, by id */
    private fun tiles(r: SettingsRoom): Map<String, RectF> {
        val p = r.perf; val top = contentTop(r, p)
        return p.tileIds().zip(p.focusTargets().take(p.tileIds().size)).associate { (id, b) -> id to RectF(b).apply { offset(0f, top) } }
    }

    private fun tap(v: View, x: Float, y: Float) {
        val d = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0); v.dispatchTouchEvent(d); d.recycle()
        val u = MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, x, y, 0); v.dispatchTouchEvent(u); u.recycle()
    }

    private fun open(r: SettingsRoom, id: String) {
        val p = r.perf
        if (p.openTile == id) return
        val i = p.tileIds().indexOf(id)
        val t = p.focusTargets()[i]
        tap(p, t.centerX(), t.centerY()); lay(r)
    }

    /** the open band's buttons, in reading order (the part's own coordinates) */
    private fun bandButtons(r: SettingsRoom): List<RectF> {
        val p = r.perf
        val all = p.focusTargets().drop(p.tileIds().size)
        val band = p.bandBox() ?: return emptyList()
        return all.filter { band.contains(it.centerX(), it.centerY()) }
    }

    private fun dll(on: Boolean) {
        val f = FrameGen.dllFile()!!
        if (on) { f.parentFile!!.mkdirs(); f.writeBytes(ByteArray(2048) { 7 }) } else f.delete()
    }

    @After fun forget() {
        runCatching { dll(false) }
        FrameGen.enabled = false; FrameGen.multiplier = 2
        FrameGen::class.java.getDeclaredField("launched").apply { isAccessible = true }.set(FrameGen, null)
        FrameGen::class.java.getDeclaredField("launchedLimit").apply { isAccessible = true }.set(FrameGen, null)
        for (f in listOf("prefs", "dir")) FrameGen::class.java.getDeclaredField(f).apply { isAccessible = true }.set(FrameGen, null)
        Tr.setChoice(app, "auto")
    }

    // ---------------------------------------------------------------- the tab

    /** ONE headless group, ONE part (the tiles), no fold, no "What it does" panel; four tiles in one row of five columns */
    @Test fun theTabIsOneHeadlessRowOfTiles() {
        val r = room()
        assertEquals(setOf(SettingsTabs.Group.FRAMES), r.open)
        val gb = r.groupBoxes.single { it.group == SettingsTabs.Group.FRAMES }
        assertEquals(View.VISIBLE, gb.box.visibility)
        assertEquals("no heading", View.GONE, gb.heading.visibility)
        assertTrue(gb.parts.single() === r.perf)
        assertEquals(View.GONE, r.preview.visibility)
        val t = tiles(r)
        assertEquals(listOf(PerformanceTileModel.LIMIT, PerformanceTileModel.FG, PerformanceTileModel.MEM, PerformanceTileModel.INDOOR), t.keys.toList())
        assertEquals(1, t.values.map { it.top }.distinct().size)
        assertEquals(16f, t.getValue(PerformanceTileModel.LIMIT).left, 0.5f)
        assertEquals(W - 16f, t.getValue(PerformanceTileModel.INDOOR).right, 0.5f)
        assertEquals(2 * GameUiTiles.TILE_W + GameUiTiles.GAP, t.getValue(PerformanceTileModel.FG).width(), 0.5f)
        // every tile is a stop; the Stream settings row presses nothing while streaming is off (no stop)
        val stops = r.pad.targetBoxes()
        for ((id, b) in t) assertTrue("$id is a stop", stops.any { it == b })
        assertEquals(4, stops.size)
        // another tab and back: the Performance band is closed
        open(r, PerformanceTileModel.MEM)
        r.show(SettingsTabs.Tab.GENERAL); r.show(SettingsTabs.Tab.PERFORMANCE); lay(r)
        assertNull(r.perf.openTile)
    }

    /** the cursor: Down from the tab row onto Frame limit, Right along the row; A opens the band and STAYS on the tile, Down into its
     *  lines, A presses one (Memory's Low-memory graphics), B back on the tile, B the tab row, B Home */
    @Test fun theCursorWalksAndBStepsBack() {
        val r = room(); hold(r)
        var left = false
        r.pad.onLeave = { left = true }
        val t = tiles(r)
        press(r, "PADDDOWN")
        assertEquals(t.getValue(PerformanceTileModel.LIMIT), r.pad.focusBox())
        press(r, "PADDRIGHT"); assertEquals(t.getValue(PerformanceTileModel.FG), r.pad.focusBox())
        press(r, "PADDRIGHT"); assertEquals(t.getValue(PerformanceTileModel.MEM), r.pad.focusBox())
        press(r, "PAD1")
        assertEquals(PerformanceTileModel.MEM, r.perf.openTile)
        assertEquals("the cursor stays on the tile", t.getValue(PerformanceTileModel.MEM), r.pad.focusBox())
        val lines = PerformanceTileModel.lines(PerformanceTileModel.MEM, r.perf.state())
        assertEquals(lines.sumOf { it.words.size }, bandButtons(r).size)
        val before = Settings.lowMemGraphics
        // down into the band, to the Low-memory graphics line, its other choice
        repeat(lines.size + 1) {
            val f = r.pad.focusBox()!!
            val b = bandButtons(r).map { RectF(it).apply { offset(0f, contentTop(r, r.perf)) } }
            val low = lines.indexOfFirst { it.key == PerformanceTileModel.L_LOWGFX }
            val want = b[lines.take(low).sumOf { it.words.size } + (if (before) 1 else 0)]
            if (f != want) press(r, if (f.centerY() < want.centerY() - 5f) "PADDDOWN" else if (f.centerX() < want.centerX()) "PADDRIGHT" else "PADDLEFT")
        }
        press(r, "PAD1")
        assertEquals(!before, Settings.lowMemGraphics)
        assertEquals("the band stays open", PerformanceTileModel.MEM, r.perf.openTile)
        press(r, "PAD2")
        assertNull(r.perf.openTile)
        assertEquals(t.getValue(PerformanceTileModel.MEM), r.pad.focusBox())
        assertTrue(!r.pad.onTabRow)
        press(r, "PAD2"); assertTrue(r.pad.onTabRow)
        assertTrue(!left)
        press(r, "PAD2"); assertTrue("B on the tab row leaves (Home)", left)
        Settings.lowMemGraphics = before
    }

    /** touch: Frame limit's band, a tap on 60 asks the launcher's limit; Frame generation with no DLL: its ONE line is Choose */
    @Test fun touchPressesTheOldRowsWrites() {
        val r = room()
        open(r, PerformanceTileModel.LIMIT)
        assertEquals(PerformanceTileModel.LIMIT, r.perf.openTile)
        val b = bandButtons(r)
        assertEquals("40 / 60 / Off", 3, b.size)
        for (x in b) assertTrue(x.height() >= 110f)
        val was = FrameGen.fpsLimit
        val k = if (was == 60) 0 else 1
        tap(r.perf, b[k].centerX(), b[k].centerY()); lay(r)
        assertEquals(FrameGen.LIMITS[k], FrameGen.fpsLimit)
        FrameGen.askLimit(was)
        // A on another tile moves the band
        dll(false)
        open(r, PerformanceTileModel.FG)
        assertEquals(PerformanceTileModel.FG, r.perf.openTile)
        val c = bandButtons(r).single()
        tap(r.perf, c.centerX(), c.centerY()); lay(r)
        assertEquals("the host's picker", 1, chose)
        // with a copy: On / Off; On brings Multiplier, Flow scale, Performance mode, Lossless.dll
        dll(true); lay(r)
        assertEquals(2, bandButtons(r).size)
        val on = bandButtons(r)[0]
        tap(r.perf, on.centerX(), on.centerY()); lay(r)
        assertTrue(FrameGen.enabled)
        assertEquals(2 + 3 + 3 + 2 + 1, bandButtons(r).size)
        // a tap outside the band closes it
        tap(r.perf, 600f, r.perf.height - 20f); lay(r)
        assertNull(r.perf.openTile)
    }

    /** Restart only while a change waits for the running game; it calls the host's restart */
    @Test fun restartOnlyWhileAChangeWaits() {
        val r = room()
        running = true
        dll(true); FrameGen.enabled = true
        FrameGen.markLaunched(); lay(r)
        open(r, PerformanceTileModel.FG)
        val keys = { PerformanceTileModel.lines(PerformanceTileModel.FG, r.perf.state()).map { it.key } }
        assertTrue(PerformanceTileModel.L_RESTART !in keys())
        FrameGen.multiplier = 3; lay(r)
        assertEquals(PerformanceTileModel.L_RESTART, keys().last())
        assertEquals(GameUiTiles.Badge.SENT, PerformanceTileModel.badge(PerformanceTileModel.FG, r.perf.state()))
        val rb = bandButtons(r).last()
        tap(r.perf, rb.centerX(), rb.centerY()); lay(r)
        assertEquals(1, restarted)
        running = false
    }

    // ---------------------------------------------------------------- the renders (PERF_SHOTS_DIR)

    /** the bake's status from its own JSON (IndoorScheduler is internal to the companion's module: by reflection) */
    private fun status(o: JSONObject): Any? {
        val cls = Class.forName("com.jrees.wowcompanion.IndoorScheduler\$Status")
        val comp = cls.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        return comp.javaClass.getMethod("of", JSONObject::class.java).apply { isAccessible = true }.invoke(comp, o)
    }

    @Test fun shots() {
        val out = System.getenv("PERF_SHOTS_DIR")?.let { File(it) } ?: return
        out.mkdirs()
        val made = ArrayList<Pair<String, Bitmap>>()
        var r = room()
        val prog = PerformanceTiles::class.java.getDeclaredField("progress").apply { isAccessible = true }
        fun shot(name: String) {
            repeat(3) { lay(r); Thread.sleep(300) }                      // the map tiles decode off the UI thread
            lay(r)
            val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.drawColor(Theme.ink); r.draw(c)
            File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            made.add(name to Bitmap.createScaledBitmap(b, W / 2, H / 2, true))
            b.recycle()
        }
        fun close() { r.perf.closeSelection(); lay(r) }
        Settings.lighterMode = false; Settings.homeMapFull = true; Settings.indoorBuild = true; Settings.lowMemGraphics = false
        prog.set(r.perf, status(JSONObject().put("phase", "BAKING").put("ready", 148).put("total", 236).put("deferred", 2)
            .put("now", "one 40 yd from you").put("list", org.json.JSONArray(listOf("b3f2a1c0d9e8f7a6b5c4d3e2f1a0b9c8: no floor found",
                "b0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5: timed out")))))
        dll(false)
        shot("perf_1_tab")
        open(r, PerformanceTileModel.LIMIT); shot("perf_2_band_frame_limit")
        open(r, PerformanceTileModel.FG); shot("perf_3_band_frame_gen_no_dll")
        dll(true); FrameGen.enabled = true; FrameGen.multiplier = 3; lay(r); shot("perf_4_band_frame_gen_on_3x")
        running = true; FrameGen.multiplier = 2; FrameGen.markLaunched(); FrameGen.multiplier = 3; lay(r); shot("perf_5_band_frame_gen_restart_waits")
        running = false
        Settings.lighterMode = true
        open(r, PerformanceTileModel.MEM); shot("perf_6_band_memory_full_lighter")
        Settings.lighterMode = false; Settings.homeMapFull = false; lay(r); shot("perf_7_band_memory_zone")
        Settings.homeMapFull = true
        open(r, PerformanceTileModel.INDOOR); shot("perf_8_band_indoor")
        val defer = bandButtons(r).last(); tap(r.perf, defer.centerX(), defer.centerY()); lay(r); shot("perf_9_band_indoor_deferred_list")
        // the cursor on an open tile, then in its band
        close(); hold(r); press(r, "PADDDOWN"); press(r, "PADDRIGHT"); press(r, "PADDRIGHT"); press(r, "PAD1"); shot("perf_10_cursor_on_open_tile")
        press(r, "PADDDOWN"); shot("perf_11_cursor_in_band")
        press(r, "PAD2"); shot("perf_12_b_back_on_tile")
        // Spanish and French: the app's words with the area's new ones merged over them (the integrator merges them for real)
        val extra = System.getenv("PERF_I18N")?.let { File(it) }?.takeIf { it.canRead() }?.let { JSONObject(it.readText()) }
        for (l in listOf("es", "fr")) {
            val base = listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
                .firstOrNull { it.canRead() }?.let { JSONObject(it.readText()) } ?: continue
            if (extra != null) for (k in extra.keys()) {
                val w = extra.getJSONObject(k).optString(l)
                if (k.contains("%s")) base.optJSONObject("templates")?.put(k, w) else base.getJSONObject("plain").put(k, w)
            }
            Tr.load(l, base.toString())
            Tr.setChoice(app, l); close(); shot("perf_13_$l")
            open(r, PerformanceTileModel.FG); shot("perf_14_${l}_band_frame_gen")
            open(r, PerformanceTileModel.MEM); shot("perf_15_${l}_band_memory")
        }
        Tr.setChoice(app, "auto")
        // no kit on the phone yet: the drawn pictures (no map tiles, no bar art)
        r = room(kit = false)
        prog.set(r.perf, status(JSONObject().put("phase", "BAKING").put("ready", 148).put("total", 236)))
        shot("perf_16_nokit")
        open(r, PerformanceTileModel.FG); shot("perf_17_nokit_band_frame_gen")
        open(r, PerformanceTileModel.MEM); shot("perf_18_nokit_band_memory")
        open(r, PerformanceTileModel.LIMIT); shot("perf_19_nokit_band_frame_limit")
        art()
        sheet(out, made)
        pictures(out)
    }

    /** every picture at the band's size and the wide well's, each state: the review sheet */
    private fun pictures(out: File) {
        art()
        val cases = listOf(
            "Frame limit 40" to (PerformanceTileModel.LIMIT to PerformanceTileModel.State(limit = 40)), "Frame limit 60" to (PerformanceTileModel.LIMIT to PerformanceTileModel.State(limit = 60)),
            "Frame limit Off" to (PerformanceTileModel.LIMIT to PerformanceTileModel.State(limit = 0)),
            "Frame gen Off" to (PerformanceTileModel.FG to PerformanceTileModel.State(hasDll = true, fgOn = false)), "Frame gen 2x" to (PerformanceTileModel.FG to PerformanceTileModel.State(hasDll = true, fgOn = true, multiplier = 2)),
            "Frame gen 4x" to (PerformanceTileModel.FG to PerformanceTileModel.State(hasDll = true, fgOn = true, multiplier = 4)),
            "Memory full" to (PerformanceTileModel.MEM to PerformanceTileModel.State(mapFull = true)), "Memory zone" to (PerformanceTileModel.MEM to PerformanceTileModel.State(mapFull = false)),
            "Indoor 63%" to (PerformanceTileModel.INDOOR to PerformanceTileModel.State(ready = 148, total = 236)), "Indoor off" to (PerformanceTileModel.INDOOR to PerformanceTileModel.State(indoorOn = false)))
        // warm the map tiles (they decode off the UI thread)
        repeat(4) {
            val c = Canvas(Bitmap.createBitmap(438, 220, Bitmap.Config.ARGB_8888))
            for ((_, v) in cases) PerformancePictures.draw(c, app, v.first, RectF(0f, 0f, 438f, 220f), v.second, 0f)
            Thread.sleep(300); org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
        val cw = 438; val ch = 220; val pad = 14; val label = 30; val cols = 3
        val rows = (cases.size + 2 + cols - 1) / cols
        val sheet = Bitmap.createBitmap(cols * (cw + pad) + pad, rows * (ch + label + pad) + pad + 140, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet); c.drawColor(Color.rgb(40, 40, 40))
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f }
        for ((i, e) in cases.withIndex()) {
            val x = pad + (i % cols) * (cw + pad); val y = pad + (i / cols) * (ch + label + pad)
            PerformancePictures.draw(c, app, e.second.first, RectF(x.toFloat(), y.toFloat(), (x + cw).toFloat(), (y + ch).toFloat()), e.second.second, 1.3f)
            c.drawText(e.first, x.toFloat(), (y + ch + 24).toFloat(), tp)
        }
        // the wide well (460 x 108) for Frame generation, Off and 3x
        val y0 = pad + rows * (ch + label + pad)
        for ((i, on) in listOf(false, true).withIndex()) {
            val x = pad + i * (460 + pad)
            PerformancePictures.draw(c, app, PerformanceTileModel.FG, RectF(x.toFloat(), y0.toFloat(), x + 460f, y0 + 108f),
                PerformanceTileModel.State(hasDll = true, fgOn = on, multiplier = 3), 0f)
            c.drawText("wide well, ${if (on) "3x" else "Off"}", x.toFloat(), (y0 + 132).toFloat(), tp)
        }
        File(out, "perf_pictures_sheet.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun sheet(out: File, made: List<Pair<String, Bitmap>>) {
        val k = 0.5f
        val cw = (W * k).toInt(); val ch = (H * k).toInt()
        val cols = 3; val rows = (made.size + cols - 1) / cols
        val pad = 12; val label = 34
        val sheet = Bitmap.createBitmap(cols * (cw + pad) + pad, rows * (ch + label + pad) + pad, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.rgb(40, 40, 40))
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 24f }
        for ((i, e) in made.withIndex()) {
            val x = pad + (i % cols) * (cw + pad); val y = pad + (i / cols) * (ch + label + pad)
            c.drawBitmap(e.second, null, android.graphics.Rect(x, y, x + cw, y + ch), Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawText(e.first, x.toFloat(), (y + ch + 26).toFloat(), tp)
        }
        File(out, "perf_sheet.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
