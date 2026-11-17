package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
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
 * HOME'S MAP CONTROLS, OFF THE DEVICE (the user, 2026-11-17: "when opening the map, pressing to the right should open some map controls,
 * pressing right again goes to the buffs and left goes back to the quests. tapping in the map area should also change it to the map
 * controls. when using map controls it should function just like the map room with the sticks. we need to change the map to show a High
 * fidelity version of the whole map in that home map, so that we can zoom out to see everything and zoom back in."). The real HomeView,
 * MapView and Map room (its art and renderer handed to the map as the host does), the module's pad route, its touches and the sticks
 * through the shared reader (PadHub's vectors):
 *  - Quests -> Right -> map controls -> Right -> buffs; Left -> controls; Left -> quests; B on the controls -> quests; a touch on the map's
 *    field -> controls (from the list and from the buffs);
 *  - the controls: the left stick moves the cursor, the right stick zooms; zoomed out past the zone the Map room's art is painted, down
 *    to the whole world; without the art the zoom stops at the zone (today's fallback);
 *  - the module closing: the sticks stop, the art is told (its pictures let go after the room's minute), the zoom back on the framing.
 * The checks run always (on a made-up three-map art set unless HMC_WORLD_ROOT is set); with HMC_SHOTS_DIR set the pages are drawn as
 * home_map_<case>.png (1240 x 1080, the bar under it). HMC_ART_ROOT = an art root laid out like the app's (its ui / fonts / icons.pak /
 * minimap are copied once under build/); HMC_WORLD_ROOT = a root with worldmap/ and seamless/ (the PC map build's, injected: the device
 * builds them itself).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeMapControlsShotsTest {
    private val out: File? = System.getenv("HMC_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    // ---------------------------------------------------------------- the art
    private val ELWYNN_RECT = floatArrayOf(-7939.583f, -10254.166f, 1535.4166f, -1935.4166f)      // t, b, l, r (continent 0)

    private fun art(world: Boolean) {
        val root = File("build/tmp/hmc-art-${if (world) "w" else "n"}").absoluteFile
        System.getenv("HMC_ART_ROOT")?.let { src ->
            for (d in listOf("ui", "fonts", "minimap")) if (!File(root, d).isDirectory) File(src, d).takeIf { it.isDirectory }?.copyRecursively(File(root, d))
            if (!File(root, "icons.pak").isFile) File(src, "icons.pak").takeIf { it.isFile }?.copyTo(File(root, "icons.pak"))
        }
        if (world) {
            val w = System.getenv("HMC_WORLD_ROOT")
            if (w != null) {
                for (d in listOf("worldmap", "seamless")) if (!File(root, d).isDirectory) File(w, d).takeIf { it.isDirectory }?.copyRecursively(File(root, d))
            } else madeUp(File(root, "worldmap"))
        } else File(root, "worldmap").deleteRecursively()
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, root)
    }

    /** a made-up art set: Azeroth, the Eastern Kingdoms and Elwynn Forest, by the game's own rects (the checks' world) */
    private fun madeUp(dir: File) {
        if (File(dir, "manifest.json").isFile) return
        dir.mkdirs()
        val maps = listOf(
            Triple(947, "Azeroth", JSONObject().put("mapType", 1).put("parent", 0).put("rect", JSONArray(listOf(1, 14824.5254, -19900.1797, 8723.958, -12087.4941)))),
            Triple(1415, "Eastern Kingdoms", JSONObject().put("mapType", 2).put("parent", 947).put("rect", JSONArray(listOf(0, 7466.6001, -16000, 16000, -19199.9004)))),
            Triple(1429, "Elwynn Forest", JSONObject().put("mapType", 3).put("parent", 1415).put("rect", JSONArray(listOf(0, -7939.583, -10254.166, 1535.4166, -1935.4166)))))
        val man = JSONObject()
        for ((id, name, o) in maps) {
            man.put("$id", JSONObject().put("name", name).put("mapType", o.getInt("mapType")).put("parent", o.getInt("parent")).put("overlays", 0))
            val d = File(dir, "$id").apply { mkdirs() }
            File(d, "index.json").writeText(o.put("uiMapID", id).put("name", name).put("w", 1002).put("h", 668).put("overlays", JSONArray()).toString())
            val b = Bitmap.createBitmap(501, 334, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val (a, z) = when (id) { 947 -> Color.rgb(40, 70, 110) to Color.rgb(30, 50, 90); 1415 -> Color.rgb(150, 130, 90) to Color.rgb(110, 96, 64)
                else -> Color.rgb(74, 110, 52) to Color.rgb(40, 70, 34) }
            c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 501f, 334f, a, z, Shader.TileMode.CLAMP) })
            File(d, "base.webp").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File(dir, "manifest.json").writeText(man.toString())
    }

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    private fun settle(n: Int = 30) { for (i in 0 until n) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() } }
    /** the Map room's art read off the UI thread (its executor), then its posts */
    private fun waitArt(room: WorldMapRoomView) {
        for (i in 0 until 200) { Thread.sleep(10); ShadowLooper.idleMainLooper(); if (room.backdropRoot(1429) != null) break }
        settle(4)
    }

    private fun mapDoc(x: Double = 0.42, y: Double = 0.62) = JSONObject().put("ok", true).put("mapID", 1429).put("zone", "Elwynn Forest")
        .put("subzone", "Goldshire").put("x", x).put("y", y).put("facing", 2.4)
        .put("rect", JSONObject().put("c", 0).put("t", ELWYNN_RECT[0].toDouble()).put("b", ELWYNN_RECT[1].toDouble())
            .put("l", ELWYNN_RECT[2].toDouble()).put("r", ELWYNN_RECT[3].toDouble()))
        .put("quests", JSONArray().put(JSONObject().put("questID", 7).put("x", 0.55).put("y", 0.48).put("title", "Kobold Camp Cleanup"))
            .put(JSONObject().put("questID", 33).put("x", 0.30).put("y", 0.80).put("title", "Wolves Across the Border")))

    private class Rig(val home: HomeView, val map: MapView, val room: WorldMapRoomView)
    private val rigs = ArrayList<Rig>()

    /** every rig lets its sticks go (PadHub is one for the whole run: a left-over subscriber would drive the next test's frames) */
    @org.junit.After fun tearDown() {
        for (r in rigs) { r.map.worldDone(); r.home.giveBack(); r.room.backdropDone(lowMemory = true) }
        rigs.clear()
        feed(0, 0f, 0f); feed(1, 0f, 0f)
        ShadowLooper.idleMainLooper()
    }

    private fun rig(world: Boolean = true, buffs: Boolean = true): Rig {
        art(world)
        HomeSettings.init(app)
        Settings.init(app)
        // on a window, as on the device: the room's posts (its art read) and isShown (the sticks' gate) need it
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val top = FrameLayout(act)
        act.setContentView(top)
        val holder = FrameLayout(act)
        val map = MapView(act); val cast = CastView(act) { }; val chat = ChatView(act) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        top.addView(holder)
        val room = WorldMapRoomView(act).apply { visibility = View.GONE }
        top.addView(room)
        room.loadArt()
        waitArt(room)
        if (world) assertTrue("the art set read (${room.model.maps.keys})", room.backdropRoot(1429) != null)
        map.worldArt = room.homeArt
        val home = HomeView(act, map, cast, chat) { }
        top.addView(home, FrameLayout.LayoutParams(W, H))
        home.borrow()
        lay(home, W, H)
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        home.onFull(JSONObject().put("type", "full").put("map", JSONObject().put("zone", "Elwynn Forest").put("pvp", "contested"))
            .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true))), true)
        map.update(mapDoc(), 0)
        room.update(mapDoc())
        if (buffs) home.onAuras(JSONObject().put("type", "auras").put("now", 100.0)
            .put("b", JSONArray().put(JSONObject().put("id", 1).put("n", "Lightning Shield").put("i", 136051).put("exp", 600.0))
                .put(JSONObject().put("id", 2).put("n", "Power Word: Fortitude").put("i", 135987).put("exp", 1800.0)))
            .put("d", JSONArray().put(JSONObject().put("id", 3).put("n", "Weakened Soul").put("i", 135871).put("exp", 112.0))), true)
        val q = JSONObject().put("plv", 6).put("list", JSONArray()
            .put(JSONObject().put("id", 7).put("title", "Kobold Camp Cleanup").put("lv", 6).put("watch", 1).put("focus", true).put("zone", "Elwynn Forest")
                .put("obj", JSONArray().put(JSONObject().put("t", "Kobold Vermin slain: 4/10"))))
            .put(JSONObject().put("id", 33).put("title", "Wolves Across the Border").put("lv", 6).put("watch", 1).put("zone", "Elwynn Forest")
                .put("obj", JSONArray().put(JSONObject().put("t", "Tough Wolf Meat: 2/8")))))
        map.onQuests(q); home.onQuests(q, true)
        home.tick()
        ShadowLooper.idleMainLooper()
        lay(home, W, H)
        HomeView::class.java.getDeclaredMethod("changed").apply { isAccessible = true }.invoke(home)
        settle()
        lay(home, W, H)
        assertTrue(home.openMapModule())
        settle(); lay(home, W, H)
        return Rig(home, map, room).also { rigs.add(it) }
    }

    private var padN = 0
    private fun key(home: HomeView, k: String) { home.auraPad(JSONObject().put("k", k).put("n", ++padN)); ShadowLooper.idleMainLooper() }

    /** a stick held for [ms] (PadHub's shared reader: what the Map room's driver reads on the device) */
    private fun stick(r: Rig, which: Int, x: Float, y: Float, ms: Int) {
        feed(which, x, y)
        for (t in 0 until ms step 16) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper(); draw(r.home) }
        feed(which, 0f, 0f)
        settle(4)
    }
    /** PadHub (internal to the companion's module) hands every subscriber the vector, as its reader does */
    private fun feed(which: Int, x: Float, y: Float) {
        val k = Class.forName("com.jrees.wowcompanion.PadHub")
        k.getMethod("feedVector", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .invoke(k.getField("INSTANCE").get(null), which, x, y)
    }

    private fun draw(home: HomeView): Bitmap {
        val bm = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm); c.drawColor(Theme.ink)
        home.draw(c)
        return bm
    }

    private fun shot(r: Rig, name: String) {
        val d = out ?: return
        d.mkdirs()
        val bm = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        val strip = Strip(app) { }
        for (pass in 0..3) {
            val c = Canvas(bm); c.drawColor(Theme.ink)
            r.home.draw(c)
            lay(strip, W, Strip.HEIGHT)
            c.save(); c.translate(0f, H.toFloat()); strip.draw(c); c.restore()
            // the pictures decode off the UI thread (the room's executor, MinimapTiles'): their posts, then a draw with them
            ShadowLooper.idleMainLooper(); Thread.sleep(200); ShadowLooper.idleMainLooper()
        }
        File(d, "home_map_$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapBox(home: HomeView): RectF {
        val f = HomeView::class.java.getDeclaredField("now"); f.isAccessible = true
        val now = f.get(home) as Map<Any, RectF>
        return RectF(now.entries.firstOrNull { it.key.toString() == "MAP" }?.value ?: error("no MAP: ${now.keys}"))
    }

    private fun tap(home: HomeView, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        home.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        home.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0))
        ShadowLooper.idleMainLooper()
    }

    private fun st(r: Rig) = r.map.controlsState()      // cursor u, v, zoom, floor, art painted, sticks read

    // ---------------------------------------------------------------- the pages

    @Test fun rightRightLeftLeftWalkThePages() {
        val r = rig()
        val home = r.home
        assertEquals("the module opens on its quest list", HomeMapPages.Page.QUESTS, home.mapPageNow())
        assertFalse(r.map.controls)
        shot(r, "quests")
        key(home, "PADDRIGHT")
        assertEquals("Right: the map controls", HomeMapPages.Page.CONTROLS, home.mapPageNow())
        assertTrue("... the map takes the sticks", r.map.controls && st(r)[5] == 1f)
        assertEquals("no aura cursor yet", null, home.cornerFocus())
        key(home, "PADDRIGHT")
        assertEquals("Right again: the buffs", HomeMapPages.Page.BUFFS, home.mapPageNow())
        assertEquals("aura", home.cornerFocus())
        assertFalse("the sticks are the game's again", r.map.controls || st(r)[5] == 1f)
        shot(r, "buffs")
        // the cursor lands on the buffs' leftmost icon (nearest the map): Left off it is the controls
        key(home, "PADDLEFT")
        assertEquals("Left off the buffs: the map controls", HomeMapPages.Page.CONTROLS, home.mapPageNow())
        assertEquals(null, home.cornerFocus())
        key(home, "PADDLEFT")
        assertEquals("Left again: the quests", HomeMapPages.Page.QUESTS, home.mapPageNow())
        assertFalse(r.map.controls)
    }

    @Test fun bOnTheControlsGoesBackToTheListThenTheModuleCloses() {
        val r = rig()
        key(r.home, "PADDRIGHT")
        key(r.home, "PAD2")
        assertEquals("B: the module's default page", HomeMapPages.Page.QUESTS, r.home.mapPageNow())
        assertEquals("MAP", r.home.flow.full.toString())
        key(r.home, "PAD2")
        assertEquals("B again on the list: the module closes, as before", "null", r.home.flow.full.toString())
    }

    @Test fun aTouchOnTheMapIsTheMapControls() {
        val r = rig()
        val m = mapBox(r.home)
        tap(r.home, m.left + m.width() * 0.72f, m.top + m.height() * 0.55f)
        assertEquals("a touch on the field: the controls", HomeMapPages.Page.CONTROLS, r.home.mapPageNow())
        key(r.home, "PADDRIGHT"); assertEquals(HomeMapPages.Page.BUFFS, r.home.mapPageNow())
        tap(r.home, m.left + m.width() * 0.6f, m.top + m.height() * 0.6f)
        assertEquals("... from the buffs too", HomeMapPages.Page.CONTROLS, r.home.mapPageNow())
        assertEquals("the aura cursor left", null, r.home.cornerFocus())
    }

    @Test fun theListKeysStayTheListsAndTheControlsLeaveTheList() {
        val r = rig()
        key(r.home, "PADDRIGHT")
        val before = st(r)
        key(r.home, "PADDDOWN"); key(r.home, "PADDUP")
        assertEquals("up / down on the controls move nothing", before[0], st(r)[0], 0f)
        key(r.home, "PADRSHOULDER")
        assertEquals("RB zooms in a step (x1.6)", before[2] * 1.6f, st(r)[2], 0.01f)
        key(r.home, "PADLSHOULDER")
        assertEquals("LB zooms out a step", before[2], st(r)[2], 0.01f)
        key(r.home, "PADBACK"); assertTrue("Select / View: the filter, as on the list", r.map.filterShown())
        key(r.home, "PAD2"); assertFalse("B closes the filter first", r.map.filterShown())
        assertEquals(HomeMapPages.Page.CONTROLS, r.home.mapPageNow())
    }

    // ---------------------------------------------------------------- the sticks and the whole map

    @Test fun theSticksMoveTheCursorAndZoomOutToTheWholeWorld() {
        val r = rig()
        key(r.home, "PADDRIGHT")
        settle(); draw(r.home)
        val s0 = st(r)
        assertTrue("with the art the zoom goes past the zone (floor ${s0[3]})", s0[3] < 0.5f)
        stick(r, 0, 0.9f, 0f, 400)
        val s1 = st(r)
        assertTrue("the left stick moves the cursor right (${s0[0]} -> ${s1[0]})", s1[0] > s0[0] + 0.01f)
        assertEquals("... not down", s0[1], s1[1], 0.01f)
        // in: the right stick up
        stick(r, 1, 0f, -1f, 600)
        val s2 = st(r)
        assertTrue("the right stick up zooms in (${s1[2]} -> ${s2[2]})", s2[2] > s1[2] * 1.5f)
        shot(r, "controls_in")
        // out: down, a long while, to the floor (the whole world)
        stick(r, 1, 0f, 1f, 4500)
        val s3 = st(r)
        assertEquals("the right stick down reaches the whole map", s3[3], s3[2], 0.001f)
        draw(r.home)
        assertTrue("the Map room's art is painted under the map", st(r)[4] == 1f)
        assertTrue("... through the room's own pictures (one cache)", r.room.backdropInUse)
        shot(r, "controls_world")
        // back in to the zone: the minimap tiles take over again, the art no longer painted
        stick(r, 1, 0f, -1f, 3000)
        draw(r.home)
        assertTrue("zoomed back in past the zone (${st(r)[2]})", st(r)[2] >= 1f)
        assertFalse("... no art under it", st(r)[4] == 1f)
    }

    @Test fun withoutTheArtTheZoomStopsAtTheZoneAsBefore() {
        val r = rig(world = false)
        key(r.home, "PADDRIGHT")
        settle(); draw(r.home)
        assertEquals("no art set: the zone is the floor", 1f, st(r)[3], 0f)
        stick(r, 1, 0f, 1f, 3000)
        draw(r.home)
        assertEquals("the right stick stops at the whole zone", 1f, st(r)[2], 0.001f)
        assertFalse(st(r)[4] == 1f)
    }

    @Test fun theModuleClosingLetsTheSticksAndTheArtGo() {
        val r = rig()
        key(r.home, "PADDRIGHT")
        stick(r, 1, 0f, 1f, 4500)
        draw(r.home)
        assertTrue(r.room.backdropInUse && r.room.pictureBytes > 0L)
        key(r.home, "PAD2"); key(r.home, "PAD2")              // the list, then the module closes
        settle()
        assertFalse("closed: the sticks stop", r.map.controls || st(r)[5] == 1f)
        assertFalse("... the art is told", r.room.backdropInUse)
        assertEquals("... the zoom back on the module's framing for the next opening", 1f, st(r)[2], 0.001f)
        assertTrue("the pictures wait the room's minute", r.room.pictureBytes > 0L)
        ShadowSystemClock.advanceBy(Duration.ofMillis(MapRoomLetGo.AFTER_MS + 1000L)); ShadowLooper.idleMainLooper()
        assertEquals("... then they go", 0L, r.room.pictureBytes)
    }

    @Test fun theLighterModelsLetThePicturesGoAtOnce() {
        val r = rig()
        key(r.home, "PADDRIGHT")
        stick(r, 1, 0f, 1f, 4500)
        draw(r.home)
        assertTrue(r.room.pictureBytes > 0L)
        r.room.backdropDone(lowMemory = true)
        assertEquals("the 8 / 12 GB models and Lighter mode: at once", 0L, r.room.pictureBytes)
    }
}
