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
        // the choreographer is one for the whole run: as it was, for the next class (DialogViewRollsTest counted on its frames)
        org.robolectric.shadows.ShadowChoreographer.setPaused(false)
        frameDelayWas?.let { org.robolectric.shadows.ShadowChoreographer.setFrameDelay(it) }
    }
    private var frameDelayWas: Duration? = null

    /** the pictures decode off the UI thread (the room's executor): wait for them to land, a few frames at a time */
    private fun waitPictures(r: Rig, ms: Long = 8_000L) {
        val until = System.nanoTime() + ms * 1_000_000L
        var last = -1L
        var still = 0
        while (System.nanoTime() < until) {
            draw(r.home); Thread.sleep(40); settle(2)
            val b = r.room.pictureBytes
            if (b == last && b > 0L) { if (++still >= 5) return } else still = 0
            last = b
        }
    }
    private fun mb(b: Long) = "%.1f MB".format(b / 1048576.0)
    private val report = StringBuilder()
    private fun note(l: String) { println("HMC-MEM $l"); report.append(l).append('\n') }

    private fun rig(world: Boolean = true, buffs: Boolean = true, full: Boolean = true): Rig {
        // frames run only as the clock moves (the sticks' and the reticle's postOnAnimation loops would otherwise spin in one idle)
        if (frameDelayWas == null) frameDelayWas = org.robolectric.shadows.ShadowChoreographer.getFrameDelay()
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
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
        map.worldFull = { full }                 // Settings > Performance > Home map: full detail (the test JVM has no Thor model)
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

    // ---------------------------------------------------------------- #584: the sticks only while the map holds the controller

    @Test fun homesSticksWaitForTheModulesHold() {
        val r = rig()
        val m = mapBox(r.home)
        tap(r.home, m.left + m.width() * 0.72f, m.top + m.height() * 0.55f)      // the controls by touch: no press, no hold answer yet
        assertEquals(HomeMapPages.Page.CONTROLS, r.home.mapPageNow())
        settle(); draw(r.home)
        assertFalse("asked, not answered: the sticks are the game's", r.home.mapSticksLive())
        val s0 = st(r)
        stick(r, 0, 0.9f, 0f, 400)
        assertEquals("#584: the stick walks the character, the cursor stays", s0[0], st(r)[0], 0.0001f)
        val z0 = st(r)[2]
        stick(r, 1, 0f, -1f, 600)
        // (the module's own framing may still ease a little after the touch: the stick's zoom in is x1.5 and more in 600 ms)
        assertTrue("... and the right stick zooms nothing ($z0 -> ${st(r)[2]})", st(r)[2] < z0 * 1.15f)
        r.home.auraGuestAck(JSONObject().put("type", "guestack").put("op", "hold").put("ok", true))
        assertEquals(MapHoldClaim.St.HELD, r.home.mapClaimState())
        assertTrue("held: the sticks are the map's", r.home.mapSticksLive())
        stick(r, 0, 0.9f, 0f, 400)
        assertTrue("the left stick moves the cursor now (${s0[0]} -> ${st(r)[0]})", st(r)[0] > s0[0] + 0.01f)
        // the hold lost (an unexplained loss): not held, asked again, the cursor waits
        r.home.auraPad(JSONObject().put("lost", "lost"))
        assertFalse(r.home.mapSticksLive())
        assertEquals("asked again at once", MapHoldClaim.St.ASKING, r.home.mapClaimState())
        val s1 = st(r)
        stick(r, 0, 0.9f, 0f, 400)
        assertEquals(s1[0], st(r)[0], 0.0001f)
        key(r.home, "PADDDOWN")                                  // a press reached the module: held again
        assertTrue(r.home.mapSticksLive())
    }

    @Test fun theMapRoomsSticksWaitForItsHold() {
        val r = rig()
        val room = r.room
        room.visibility = View.VISIBLE
        lay(room, W, H)
        room.onShown()
        settle()
        room.draw(Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)))
        room.touchedIn()
        assertEquals("the room asks for its hold", MapHoldClaim.St.ASKING, room.claimState())
        assertFalse(room.claimSticks())
        val c0 = room.cursorNow()
        roomStick(room, 0.9f, 0f, 400)
        assertEquals("#584: no hold yet: the cursor stays (the stick is the game's)", c0[0], room.cursorNow()[0], 0.0001f)
        room.onMapAck(JSONObject().put("type", "mapack").put("op", "hold").put("ok", false).put("why", "busy"))
        assertEquals("refused: logged, still not held", MapHoldClaim.St.REFUSED, room.claimState())
        roomStick(room, 0.9f, 0f, 400)
        assertEquals(c0[0], room.cursorNow()[0], 0.0001f)
        room.onMapAck(JSONObject().put("type", "mapack").put("op", "hold").put("ok", true))
        assertTrue("held: the sticks are the room's", room.claimSticks())
        roomStick(room, 0.9f, 0f, 400)
        assertTrue("the cursor moves now (${c0[0]} -> ${room.cursorNow()[0]})", room.cursorNow()[0] > c0[0] + 0.01f)
        // the exit guard: the capture named nobody -> not held, asked again
        room.guardReask()
        assertFalse(room.claimSticks())
        val c1 = room.cursorNow()
        roomStick(room, 0.9f, 0f, 400)
        assertEquals(c1[0], room.cursorNow()[0], 0.0001f)
        room.onPad(JSONObject().put("k", "PADDDOWN").put("n", 99))     // a press: held again
        assertTrue(room.claimSticks())
        room.touchedOut(); room.onHidden()
        assertEquals(MapHoldClaim.St.OFF, room.claimState())
    }
    // #584's review: an NPC window's loss leaves B to the window (one B never closes the loot window AND the map module)
    @Test fun anNpcWindowsLossLeavesBToTheWindowUntilNobodyHolds() {
        val r = rig()
        var holder: String? = "pad:home"
        r.home.capHolderNow = { holder }
        r.home.auraGuestAck(JSONObject().put("type", "guestack").put("op", "hold").put("ok", true))
        assertEquals(MapHoldClaim.St.HELD, r.home.mapClaimState())
        assertFalse(r.home.mapFallbackReading())
        holder = "npc"                                           // a loot window opens: Interact's guestLost("npc")
        r.home.auraPad(JSONObject().put("lost", "npc"))
        assertEquals(MapHoldClaim.St.LOST, r.home.mapClaimState())
        settle(250)                                              // 4 s: far past the module's first ask
        assertFalse("the NPC window holds: Home reads no B of its own (its B closes the window only)", r.home.mapFallbackReading())
        assertTrue("... the module stays open", r.home.mapGuarding())
        holder = ""                                              // the window closed, nobody holds: the hold asked again
        settle(80)
        assertEquals("asked again once nobody holds", MapHoldClaim.St.ASKING, r.home.mapClaimState())
        assertTrue("nobody holding, no hold: B read by Home closes the map (no other holder's B to share)", r.home.mapFallbackReading())
        assertEquals("out of a fight: the note says so (no 'In a fight')", HomeView.KEPT_MAP_NOTE, r.home.mapNoteSaid)
        r.home.auraGuestAck(JSONObject().put("type", "guestack").put("op", "hold").put("ok", true))
        assertFalse("held again: the reader off", r.home.mapFallbackReading())
        // a fight takes it, the re-ask refused "combat": the fight's note, as before
        r.home.auraPad(JSONObject().put("lost", "lost"))
        r.home.auraGuestAck(JSONObject().put("type", "guestack").put("op", "hold").put("ok", false).put("why", "combat"))
        assertTrue(r.home.mapFallbackReading())
        assertEquals(HomeView.FIGHT_MAP_NOTE, r.home.mapNoteSaid)
    }

    // #584's review: the Map room's exit-guard re-asks are bounded and the note shows (never the hold cell tapped all visit)
    @Test fun theMapRoomsExitGuardReAsksAreBounded() {
        val r = rig()
        val room = r.room
        room.visibility = View.VISIBLE
        lay(room, W, H)
        room.onShown()
        settle()
        room.touchedIn()
        // a refusal another ask will not change: the guard leaves it, the note shows
        room.onMapAck(JSONObject().put("type", "mapack").put("op", "hold").put("ok", false).put("why", "no controller capture"))
        assertEquals(MapHoldClaim.St.REFUSED, room.claimState())
        settle(200)
        for (i in 0 until 5) { room.guardReask(); settle(190) }
        assertEquals("not asked again by the guard", 0, room.claimGuardAsks())
        assertEquals(MapHoldClaim.St.REFUSED, room.claimState())
        assertEquals(WorldMapRoomView.NO_HOLD_NOTE, room.noteNow())
        room.touchedOut(); room.onHidden()

        // a take that only ever answers "ok, waits" (the stale pend): MAX_GUARD_ASKS re-asks, then left at that with the note
        WorldMapRoomView::class.java.getDeclaredField("note").apply { isAccessible = true }.set(room, null)
        room.onShown(); settle()
        room.touchedIn()
        room.onMapAck(JSONObject().put("type", "mapack").put("op", "hold").put("ok", true).put("waits", true))
        assertEquals(MapHoldClaim.St.PENDING, room.claimState())
        for (i in 1..MapHoldClaim.MAX_GUARD_ASKS) {
            room.guardReask()
            assertEquals(i, room.claimGuardAsks())
            room.guardReask()
            assertEquals("a round still out is left to finish", i, room.claimGuardAsks())
            room.onMapAck(JSONObject().put("type", "mapack").put("op", "hold").put("ok", true).put("waits", true))
            settle(190)
        }
        assertEquals("no note while the guard still asks", null, room.noteNow())
        room.guardReask(); room.guardReask()
        assertEquals("bounded", MapHoldClaim.MAX_GUARD_ASKS, room.claimGuardAsks())
        assertEquals(MapHoldClaim.St.UNANSWERED, room.claimState())
        assertFalse(room.claimSticks())
        assertEquals(WorldMapRoomView.NO_HOLD_NOTE, room.noteNow())
        // a hold that lands starts the count again (a later NPC window's loss may be asked for again)
        room.onMapAck(JSONObject().put("type", "mapack").put("op", "hold").put("ok", true))
        assertEquals(MapHoldClaim.St.HELD, room.claimState())
        assertEquals(0, room.claimGuardAsks())
        room.guardReask()
        assertEquals(1, room.claimGuardAsks())
        room.touchedOut(); room.onHidden()
    }
    private fun roomStick(room: WorldMapRoomView, x: Float, y: Float, ms: Int) {
        feed(0, x, y)
        for (t in 0 until ms step 16) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() }
        feed(0, 0f, 0f)
        settle(4)
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
        waitPictures(r)
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
        waitPictures(r)
        assertTrue(r.room.pictureBytes > 0L)
        r.room.backdropDone(lowMemory = true)
        assertEquals("the 8 / 12 GB models and Lighter mode: at once", 0L, r.room.pictureBytes)
    }

    // ---------------------------------------------------------------- the setting and the memory rules (the user, 2026-11-17: "I am
    // slightly concerned about the map turning into a memory sink ... turn this into a Thor based toggle")

    @Test fun withFullDetailOffTheMapIsTodaysButTheControlsStay() {
        val r = rig(full = false)
        key(r.home, "PADDRIGHT")
        assertEquals("the controls page is there", HomeMapPages.Page.CONTROLS, r.home.mapPageNow())
        settle(); draw(r.home)
        assertEquals("off: the zone is the floor (no whole-world zoom-out)", 1f, st(r)[3], 0f)
        stick(r, 1, 0f, 1f, 3000)
        draw(r.home)
        assertEquals(1f, st(r)[2], 0.001f)
        assertFalse("no art asked for", st(r)[4] == 1f || r.room.backdropInUse)
        assertEquals("no pictures held for Home", 0L, r.room.pictureBytes)
        stick(r, 0, 0.9f, 0f, 300)
        assertTrue("the sticks still move the cursor", st(r)[5] == 1f)
    }

    @Test fun androidsLowMemoryWarningFreesTheArtAndHomeFallsBackToItsZone() {
        val r = rig()
        key(r.home, "PADDRIGHT")
        stick(r, 1, 0f, 1f, 4500)
        waitPictures(r)
        assertTrue(r.room.pictureBytes > 0L)
        val freed = r.room.trimmed(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertTrue("the warning frees what Home's map held ($freed)", freed > 0L && r.room.pictureBytes == 0L)
        assertTrue("... and pauses the art for Home", r.room.backdropPaused)
        draw(r.home); draw(r.home)
        assertEquals("Home's map is back on its zone (today's map)", 1f, st(r)[2], 0.001f)
        assertFalse(st(r)[4] == 1f)
        waitPictures(r, 1500L)
        assertEquals("nothing decoded back in while short", 0L, r.room.pictureBytes)
        key(r.home, "PAD2"); key(r.home, "PAD2")
        assertFalse("the module closing clears the pause", r.room.backdropPaused)
        assertEquals("a light warning (UI hidden) is not the low-memory one", 0L, r.room.trimmed(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
    }

    @Test fun turningTheSettingOffLetsTheArtGo() {
        var on = true
        val r = rig()
        r.map.worldFull = { on }
        key(r.home, "PADDRIGHT")
        stick(r, 1, 0f, 1f, 4500)
        waitPictures(r)
        assertTrue(r.room.backdropInUse)
        on = false
        draw(r.home); draw(r.home)
        assertFalse("off in Settings: the art is told at once", r.room.backdropInUse)
        assertEquals("... and the zoom is back on the zone", 1f, st(r)[2], 0.001f)
    }

    /**
     * THE NUMBERS (the coordinator's ask): the pictures Home's map holds (the Map room's cache, MemWatch's "worldroom") zoomed in on the
     * zone, out to the continent, out to the whole world, after the module closes and a minute later, next to the Map room's own
     * footprint on the same art. Real numbers only with HMC_WORLD_ROOT (the PC map build); the lines go to stdout and
     * HMC_SHOTS_DIR/memory.txt.
     */
    @Test fun memoryFootprint() {
        if (System.getenv("HMC_WORLD_ROOT") == null) return
        val r = rig()
        key(r.home, "PADDRIGHT")
        settle(); waitPictures(r, 2000L)
        note("zone (zoom ${"%.2f".format(st(r)[2])}): ${mb(r.room.pictureBytes)} of world-map pictures (the zone draws minimap tiles, as today)")
        var peak = 0L
        // out in steps, the peak held at each (LB: x1/1.6)
        for (i in 0 until 12) {
            key(r.home, "PADLSHOULDER"); waitPictures(r, 3000L)
            peak = maxOf(peak, r.room.pictureBytes)
            note("zoom ${"%.3f".format(st(r)[2])} (floor ${"%.3f".format(st(r)[3])}): ${mb(r.room.pictureBytes)} held, art painted ${st(r)[4] == 1f}")
        }
        note("peak zoomed out: ${mb(peak)}; the cache's cap is 72 MB (WorldMapRoomView's LruCache)")
        // the whole module walked back in
        for (i in 0 until 12) { key(r.home, "PADRSHOULDER") }
        waitPictures(r, 1500L)
        note("back on the zone: ${mb(r.room.pictureBytes)} held (the user's pick: freed on zoom-in)")
        for (i in 0 until 12) key(r.home, "PADLSHOULDER")
        waitPictures(r, 3000L)
        note("out to the world again: ${mb(r.room.pictureBytes)} (read again)")
        key(r.home, "PAD2"); key(r.home, "PAD2"); settle()
        note("module closed: ${mb(r.room.pictureBytes)} held; in use ${r.room.backdropInUse}")
        ShadowSystemClock.advanceBy(Duration.ofMillis(MapRoomLetGo.AFTER_MS + 1000L)); ShadowLooper.idleMainLooper()
        note("61 s after the close: ${mb(r.room.pictureBytes)}")
        // the Map room's own footprint on the same art: opened on the player's zone, then its LB out to the world
        r.room.visibility = View.VISIBLE
        r.room.layout(0, 0, W, H)
        r.room.onShown()
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        fun roomDraw() { for (i in 0 until 40) { r.room.draw(Canvas(bmp)); Thread.sleep(40); settle(1) } }
        roomDraw()
        note("Map room on the zone: ${mb(r.room.pictureBytes)}")
        repeat(8) { r.room.onMapPad(JSONObject().put("n", 900 + it).put("k", "PADLSHOULDER")); roomDraw() }
        note("Map room zoomed out (LB x8): ${mb(r.room.pictureBytes)}")
        out?.let { d -> d.mkdirs(); File(d, "memory.txt").writeText(report.toString()) }
    }

    /** the user's pick (2026-11-17, "Free on zoom-in"): back on the zone the big pictures go at once; zooming out reads them again */
    @Test fun zoomingBackToTheZoneFreesThePicturesAndZoomingOutReadsThemAgain() {
        val r = rig()
        key(r.home, "PADDRIGHT")
        stick(r, 1, 0f, 1f, 4500)
        waitPictures(r)
        assertTrue("out on the world: pictures held", r.room.pictureBytes > 0L && r.room.backdropInUse)
        stick(r, 1, 0f, -1f, 3000)
        draw(r.home)
        assertTrue("back on the zone (${st(r)[2]})", st(r)[2] >= 1f)
        assertEquals("... the pictures are freed at once", 0L, r.room.pictureBytes)
        assertFalse(r.room.backdropInUse)
        assertEquals("the module is still open", HomeMapPages.Page.CONTROLS, r.home.mapPageNow())
        stick(r, 1, 0f, 1f, 4500)
        waitPictures(r)
        assertTrue("zooming out again reads them again", r.room.pictureBytes > 0L && r.room.backdropInUse)
    }
}
