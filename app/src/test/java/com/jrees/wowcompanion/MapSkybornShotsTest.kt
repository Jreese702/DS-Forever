package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * THE SKYBORNE'S ISLAND ON BOTH MAPS, OFF THE DEVICE (the user, 2026-11-17: "The new Island for the skyborn is missing from both maps it
 * should be in like the upper center is where they put it in the actual game map"). Zephras Isle = UiMap 2521 (mapType 3, parent Azeroth
 * 947) on map 2991; the worldmap recipe places it on the world (947's row for 2991). The real Map room and Home's map (MapView with the
 * room's art under it, as the host wires it):
 *  - the room on the world: the island is a land layer at the world's top middle; the room opened on the island (the player there) is
 *    on the island's own picture, and LB out to the world carries the player's arrow to the island's box;
 *  - Home's map on the island zooms out past the island to the whole world (the island's place on its root is the world's box).
 * The checks run always (a made-up art set unless SKY_ART_ROOT is set: an art root laid out like the app's, worldmap/ minimap/ ui/
 * fonts/ copied once under build/); with SKY_SHOTS_DIR the views are drawn as <case>.png.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MapSkybornShotsTest {
    private val out: File? = System.getenv("SKY_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    // the client's rects ([continent, top, bottom, left, right]; the recipe's): Zephras Isle on map 2991
    private val ISLE = listOf(2991, 4956.25, 1247.917, 4231.25, -1331.25)

    private fun art() {
        val src = System.getenv("SKY_ART_ROOT")
        val root = File("build/tmp/sky-art-${if (src != null) "r" else "m"}").absoluteFile
        if (src != null) {
            for (d in listOf("worldmap", "seamless", "minimap", "ui", "fonts")) if (!File(root, d).isDirectory)
                File(src, d).takeIf { it.isDirectory }?.copyRecursively(File(root, d))
        } else madeUp(File(root, "worldmap"))
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, root)
        MinimapTiles.refresh()
    }

    /** the world, both continents and the island, by the game's own rects (plain colours) */
    private fun madeUp(dir: File) {
        if (File(dir, "manifest.json").isFile) return
        dir.mkdirs()
        val maps = listOf(
            Triple(947, "Azeroth", JSONObject().put("mapType", 1).put("parent", 0).put("rect", JSONArray(listOf(1, 14824.5254, -19900.1797, 8723.958, -12087.4941)))),
            Triple(1414, "Kalimdor", JSONObject().put("mapType", 2).put("parent", 947).put("rect", JSONArray(listOf(1, 12799.9004, -11733.2998, 17066.5996, -19733.2109)))),
            Triple(1415, "Eastern Kingdoms", JSONObject().put("mapType", 2).put("parent", 947).put("rect", JSONArray(listOf(0, 7466.6001, -16000, 16000, -19199.9004)))),
            Triple(2521, "Zephras Isle", JSONObject().put("mapType", 3).put("parent", 947).put("rect", JSONArray(ISLE))))
        val man = JSONObject()
        for ((id, name, o) in maps) {
            man.put("$id", JSONObject().put("name", name).put("mapType", o.getInt("mapType")).put("parent", o.getInt("parent")).put("overlays", 0))
            val d = File(dir, "$id").apply { mkdirs() }
            File(d, "index.json").writeText(o.put("uiMapID", id).put("name", name).put("w", 1002).put("h", 668).put("overlays", JSONArray()).toString())
            val b = Bitmap.createBitmap(501, 334, Bitmap.Config.ARGB_8888)
            val (a, z) = when (id) { 947 -> Color.rgb(40, 70, 110) to Color.rgb(30, 50, 90); 2521 -> Color.rgb(170, 140, 90) to Color.rgb(120, 96, 60)
                else -> Color.rgb(150, 130, 90) to Color.rgb(110, 96, 64) }
            Canvas(b).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 501f, 334f, a, z, Shader.TileMode.CLAMP) })
            File(d, "base.webp").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File(dir, "manifest.json").writeText(man.toString())
    }

    private fun settle(n: Int = 30) { for (i in 0 until n) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() } }
    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    private fun <T> field(v: Any, name: String): T {
        val f = v.javaClass.getDeclaredField(name); f.isAccessible = true
        @Suppress("UNCHECKED_CAST") return f.get(v) as T
    }
    private fun artReady(room: WorldMapRoomView): Boolean = field(room, "artReady")

    /** a quest on the island (Map.lua's mapquests) and the player on it, by the island's own 0..1 */
    private fun doc(x: Double = 0.56, y: Double = 0.47) = JSONObject().put("ok", true).put("mapID", 2521).put("zone", "Zephras Isle")
        .put("x", x).put("y", y).put("facing", 2.4)
        .put("rect", JSONObject().put("c", ISLE[0]).put("t", ISLE[1]).put("b", ISLE[2]).put("l", ISLE[3]).put("r", ISLE[4]))
        .put("quests", JSONArray().put(JSONObject().put("questID", 90001).put("x", 0.44).put("y", 0.36).put("title", "Winds of the Spire")))
    private fun questsDoc() = JSONObject().put("plv", 4).put("list", JSONArray()
        .put(JSONObject().put("id", 90001).put("title", "Winds of the Spire").put("lv", 3).put("watch", 1).put("focus", true).put("zone", "Zephras Isle")
            .put("obj", JSONArray().put(JSONObject().put("t", "Leyline read: 1/3")))))

    private fun room(player: Boolean): WorldMapRoomView {
        art()
        Settings.init(app)
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val v = WorldMapRoomView(act)
        act.setContentView(v)
        v.loadArt()
        val until = System.currentTimeMillis() + 15_000L
        while (!artReady(v) && System.currentTimeMillis() < until) { ShadowLooper.idleMainLooper(); Thread.sleep(10) }
        assertTrue("the art read", artReady(v))
        if (player) {
            v.update(doc())
            v.onQuests(questsDoc())
            v.onMapQuests(JSONObject().put("type", "mapquests").put("maps", JSONObject().put("2521", JSONArray()
                .put(JSONObject().put("q", 90001).put("x", 0.44).put("y", 0.36).put("t", "Winds of the Spire")))))
        }
        lay(v, W, H)
        v.onShown()
        frames(v)
        return v
    }

    private fun frames(v: View, n: Int = 4): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        for (i in 0 until n) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper()
            val c = Canvas(b); c.drawColor(Theme.ink); v.draw(c)
        }
        return b
    }
    /** the pictures decode off the UI thread: frames until the cache stops growing */
    private fun pictures(v: WorldMapRoomView) {
        var last = -1L; var still = 0
        val until = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < until) {
            frames(v, 1); Thread.sleep(40)
            val b = v.pictureBytes
            if (b == last && b > 0L) { if (++still >= 6) return } else still = 0
            last = b
        }
    }
    private fun shot(v: WorldMapRoomView, name: String) {
        val d = out ?: return
        pictures(v)
        val b = frames(v)
        d.mkdirs()
        File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private var n = 0
    private fun key(v: WorldMapRoomView, k: String, settleMs: Int = 900) {
        v.onMapPad(JSONObject().put("n", ++n).put("k", k)); settle(settleMs / 16); frames(v, 2)
    }

    @Test fun theRoomShowsTheIslandAtTheWorldsTopMiddle() {
        val v = room(player = false)
        assertEquals("opens on the world", 947, field<Int>(v, "canvas"))
        val m = v.model
        assertNotNull("the recipe's row for map 2991 is read", m.assignOf(947, 2991))
        val isle = m.layersOn(947) { emptyList() }.firstOrNull { it.id == 2521 }
        assertNotNull("Zephras Isle is a land on the world", isle)
        val p = isle!!.place
        assertTrue("upper centre: ${p.toList()}", (p[0] + p[2]) / 2 in 0.42f..0.66f && (p[1] + p[3]) / 2 < 0.3f)
        shot(v, "maproom_world")
    }

    @Test fun theRoomZoomsIntoTheIslandAndBackOutWithThePlayer() {
        val v = room(player = true)
        assertEquals("opens on the player's island", 2521, field<Int>(v, "shown"))
        assertEquals("... its own canvas (its whole picture)", 2521, field<Int>(v, "canvas"))
        shot(v, "maproom_island")
        // LB out, step by step, up to the world: the island's canvas hands over to the world's
        repeat(10) { if (field<Int>(v, "shown") != 947) key(v, "PADLSHOULDER") }
        settle(120)
        assertEquals("out to the world", 947, field<Int>(v, "canvas"))
        assertEquals("... the world named", 947, field<Int>(v, "shown"))
        // the player's arrow carried up onto the world lands in the island's box
        val at = v.model.carryUp(2521, 0.56f, 0.47f, 947)!!
        val p = v.model.placeOn(2521, 947)!!
        assertTrue("the arrow in the island's box ${at.toList()} ${p.toList()}", at[0] in p[0]..p[2] && at[1] in p[1]..p[3])
        shot(v, "maproom_world_player_mid")
        // on out to the whole world: the arrow and the quest stay on the island at the top middle
        repeat(4) { key(v, "PADLSHOULDER") }
        settle(120)
        assertEquals("the whole world", 1f, field<Float>(v, "zoom"), 0.01f)
        shot(v, "maproom_world_player")
    }

    @Test fun homesMapOnTheIslandZoomsOutToTheWholeWorld() {
        art()
        HomeSettings.init(app); Settings.init(app)
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val top = FrameLayout(act)
        act.setContentView(top)
        val room = WorldMapRoomView(act).apply { visibility = View.GONE }
        top.addView(room)
        room.loadArt()
        for (i in 0 until 300) { Thread.sleep(10); ShadowLooper.idleMainLooper(); if (room.backdropRoot(2521) != null) break }
        val root = room.backdropRoot(2521)
        assertNotNull("the island has a place on its root (the world) for Home's zoom-out", root)
        assertTrue("... the world's top middle: ${root!!.toList()}", (root[0] + root[2]) / 2 in 0.42f..0.66f && (root[1] + root[3]) / 2 < 0.3f)
        val map = MapView(act)
        top.addView(map, FrameLayout.LayoutParams(W, H))
        map.worldArt = room.homeArt
        map.worldFull = { true }
        map.update(doc(), 0)
        map.onQuests(questsDoc())
        lay(map, W, H)
        if (frameDelayWas == null) frameDelayWas = org.robolectric.shadows.ShadowChoreographer.getFrameDelay()
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        map.controls = true                                  // Home's map controls page: the sticks are the map's
        settle(); drawMap(map)
        val s0 = map.controlsState()
        assertTrue("with the art the zoom goes past the island (floor ${s0[3]})", s0[3] < 0.2f)
        mapShot(map, "home_island")
        // out to the floor (the right stick down a long while): the whole world, the island's art at its place
        stick(map, 1, 0f, 1f, 4500)
        drawMap(map)
        assertEquals("the right stick down reaches the whole world", map.controlsState()[3], map.controlsState()[2], 0.001f)
        assertTrue("the Map room's art is painted under Home's map", map.controlsState()[4] == 1f)
        mapShot(map, "home_world")
        // back in on the island: its terrain tiles (map 2991's minimap) under the marks, the art no longer painted
        stick(map, 1, 0f, -1f, 2200)
        drawMap(map)
        assertTrue("zoomed back in to the island (${map.controlsState()[2]})", map.controlsState()[2] >= 0.95f)
        mapShot(map, "home_island_in")
        map.worldDone(); room.backdropDone(lowMemory = true)
        feed(0, 0f, 0f); feed(1, 0f, 0f); ShadowLooper.idleMainLooper()
    }

    private var frameDelayWas: Duration? = null
    @org.junit.After fun tearDown() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(false)
        frameDelayWas?.let { org.robolectric.shadows.ShadowChoreographer.setFrameDelay(it) }
    }
    /** a stick held for [ms] (PadHub's shared reader, as HomeMapControlsShotsTest drives it) */
    private fun stick(map: MapView, which: Int, x: Float, y: Float, ms: Int) {
        feed(which, x, y)
        for (t in 0 until ms step 16) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper(); drawMap(map) }
        feed(which, 0f, 0f)
        settle(4)
    }
    private fun feed(which: Int, x: Float, y: Float) {
        val k = Class.forName("com.jrees.wowcompanion.PadHub")
        k.getMethod("feedVector", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .invoke(k.getField("INSTANCE").get(null), which, x, y)
    }

    private fun drawMap(map: MapView): Bitmap {
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm); c.drawColor(Theme.ink); map.draw(c)
        return bm
    }
    private fun mapShot(map: MapView, name: String) {
        val d = out ?: return
        var bm = drawMap(map)
        for (pass in 0..30) { ShadowLooper.idleMainLooper(); Thread.sleep(200); ShadowLooper.idleMainLooper(); bm = drawMap(map) }
        d.mkdirs()
        File(d, "$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
