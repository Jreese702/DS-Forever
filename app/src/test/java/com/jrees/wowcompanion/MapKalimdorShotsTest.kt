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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * KALIMDOR ON BOTH MAPS, AND HOME'S TERRAIN ZOOM-OUT (the user, 2026-11-17, on test build 1483):
 *  - "both maps have this problem when zooming in to Kalimdor it just changes to the ocean of the eastern kingdoms map": the continents'
 *    frames on the world overlap (each is its painting's whole sheet), and the room took the FIRST frame covering the view (Eastern
 *    Kingdoms) and bounded the pan by the box nearest the view's middle - a zoom from the world's middle toward Durotar was held on
 *    Eastern Kingdoms' west sea, and Home's zoomed-out backdrop east of Kalimdor was drawn on Eastern Kingdoms' canvas;
 *  - "on the high fidelity map, I dont think that I want the transition to the map overlay ... kept it as high fidelity there or made it
 *    an option toggle": Settings > Performance > Map style when zoomed out, Terrain (default) / Painted, Home's map only;
 *  - "when toggling over to buffs the cursor reappears on polestar which is misleading": the buffs page draws no list cursor or hints.
 * The checks run on a made-up art set (the game's own rects and the recipe's world assignments; Kalimdor green, Eastern Kingdoms
 * magenta, made-up minimap tiles) unless MK_WORLD_ROOT (worldmap/, seamless/) and MK_ART_ROOT (ui/, fonts/, minimap/) are set; with
 * MK_SHOTS_DIR the cases are drawn there (and memory.txt for the terrain zoom-out).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MapKalimdorShotsTest {
    private val out: File? = System.getenv("MK_SHOTS_DIR")?.let { File(it) }
    private val realWorld = System.getenv("MK_WORLD_ROOT")
    private val realArt = System.getenv("MK_ART_ROOT")
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    // the game's rects ([c, top, bottom, left, right]: world X north, world Y west)
    private val DUROTAR = doubleArrayOf(1.0, 1808.3333, -1716.6666, -1962.4999, -7249.9995)
    private val KAL_GREEN = Color.rgb(60, 150, 60)
    private val EK_MAGENTA = Color.rgb(200, 40, 170)
    private val TILE_K = Color.rgb(0, 170, 0)
    private val TILE_EK = Color.rgb(220, 0, 0)
    /** the made-up minimap: Kalimdor's ADTs round Durotar, but NOT (40, 31); Eastern Kingdoms' over the same numbers, (40, 31) too */
    private val HOLE = 40 to 31

    // ---------------------------------------------------------------- the art
    private fun art(minimapK: Boolean = true) {
        val root = File("build/tmp/mk-art-${if (realWorld != null) "r" else "m"}-${if (minimapK) "k" else "nok"}").absoluteFile
        if (realWorld != null) {
            for (d in listOf("worldmap", "seamless")) if (!File(root, d).isDirectory) File(realWorld, d).takeIf { it.isDirectory }?.copyRecursively(File(root, d))
        } else madeUpWorld(File(root, "worldmap"))
        if (realArt != null) {
            for (d in listOf("ui", "fonts", "minimap")) if (!File(root, d).isDirectory) File(realArt, d).takeIf { it.isDirectory }?.copyRecursively(File(root, d))
        } else madeUpMinimap(File(root, "minimap"))
        if (!minimapK) File(root, "minimap/1").deleteRecursively()
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, root)
        MinimapTiles.refresh(); TerrainOverview.refresh(); TerrainOverview.load()
        TerrainOverview.dir = File("build/tmp/mk-cache").absoluteFile
    }

    private fun madeUpWorld(dir: File) {
        if (File(dir, "manifest.json").isFile) return
        dir.mkdirs()
        val maps = listOf(
            Triple(947, "Azeroth", JSONObject().put("mapType", 1).put("parent", 0).put("rect", JSONArray(listOf(1, 14824.5254, -19900.1797, 8723.958, -12087.4941)))),
            Triple(1414, "Kalimdor", JSONObject().put("mapType", 2).put("parent", 947).put("rect", JSONArray(listOf(1, 12799.9004, -11733.2998, 17066.5996, -19733.2109)))),
            Triple(1415, "Eastern Kingdoms", JSONObject().put("mapType", 2).put("parent", 947).put("rect", JSONArray(listOf(0, 7466.6001, -16000, 16000, -19199.9004)))),
            Triple(1411, "Durotar", JSONObject().put("mapType", 3).put("parent", 1414).put("rect", JSONArray(DUROTAR.toList()))),
            Triple(1429, "Elwynn Forest", JSONObject().put("mapType", 3).put("parent", 1415).put("rect", JSONArray(listOf(0, -7939.583, -10254.166, 1535.4166, -1935.4166)))))
        val man = JSONObject()
        for ((id, name, o) in maps) {
            man.put("$id", JSONObject().put("name", name).put("mapType", o.getInt("mapType")).put("parent", o.getInt("parent")).put("overlays", 0))
            val d = File(dir, "$id").apply { mkdirs() }
            File(d, "index.json").writeText(o.put("uiMapID", id).put("name", name).put("w", 1002).put("h", 668).put("overlays", JSONArray()).toString())
            val b = Bitmap.createBitmap(501, 334, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val (a, z) = when (id) { 947 -> Color.rgb(40, 70, 110) to Color.rgb(30, 50, 90); 1414 -> KAL_GREEN to KAL_GREEN
                1415 -> EK_MAGENTA to EK_MAGENTA; else -> Color.rgb(150, 120, 70) to Color.rgb(110, 90, 50) }
            c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 501f, 334f, a, z, Shader.TileMode.CLAMP) })
            File(d, "base.webp").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File(dir, "manifest.json").writeText(man.toString())
    }

    private fun solid(f: File, color: Int) {
        if (f.isFile) return
        f.parentFile.mkdirs()
        val b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888); b.eraseColor(color)
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun madeUpMinimap(dir: File) {
        if (File(dir, "index.json").isFile) return
        val k = JSONArray(); val e = JSONArray()
        for (col in 36..45) for (row in 27..36) if ((col to row) != HOLE) { solid(File(dir, "1/${col}_$row.webp"), TILE_K); k.put(JSONArray(listOf(col, row))) }
        for (col in 30..50) for (row in 25..40) { solid(File(dir, "0/${col}_$row.webp"), TILE_EK); e.put(JSONArray(listOf(col, row))) }
        File(dir, "index.json").writeText(JSONObject().put("px", 512).put("adt", 533.33333).put("continents", JSONObject()
            .put("0", JSONObject().put("dir", "azeroth").put("tiles", e)).put("1", JSONObject().put("dir", "kalimdor").put("tiles", k))).toString())
    }

    // ---------------------------------------------------------------- the rig (HomeMapControlsShotsTest's)
    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    private fun settle(n: Int = 30) { for (i in 0 until n) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() } }

    private val ELWYNN = doubleArrayOf(0.0, -7939.583, -10254.166, 1535.4166, -1935.4166)
    private var zoneId = 1411
    private fun mapDoc(): JSONObject {
        val (name, r) = if (zoneId == 1429) "Elwynn Forest" to ELWYNN else "Durotar" to DUROTAR
        return JSONObject().put("ok", true).put("mapID", zoneId).put("zone", name).put("subzone", "")
        .put("x", 0.52).put("y", 0.44).put("facing", 2.4)
        .put("rect", JSONObject().put("c", r[0].toInt()).put("t", r[1]).put("b", r[2]).put("l", r[3]).put("r", r[4]))
        .put("quests", JSONArray().put(JSONObject().put("questID", 7).put("x", 0.55).put("y", 0.48).put("title", "Sarkoth"))
            .put(JSONObject().put("questID", 8).put("x", 0.47).put("y", 0.50).put("title", "Vile Familiars")))
    }

    private fun questsDoc(): JSONObject {
        return JSONObject().put("plv", 6).put("list", JSONArray()
            .put(JSONObject().put("id", 7).put("title", "Sarkoth").put("lv", 5).put("watch", 1).put("focus", true).put("zone", "Durotar")
                .put("obj", JSONArray().put(JSONObject().put("t", "Sarkoth's Mangled Claw: 0/1"))))
            .put(JSONObject().put("id", 9).put("title", "Lazy Peons").put("lv", 4).put("watch", 1).put("ready", true).put("done", true)
                .put("zone", "Durotar"))
            .put(JSONObject().put("id", 8).put("title", "Vile Familiars").put("lv", 3).put("zone", "Durotar")
                .put("obj", JSONArray().put(JSONObject().put("t", "Vile Familiar slain: 2/12")))))
    }

    private class Rig(val home: HomeView, val map: MapView, val room: WorldMapRoomView)
    private val rigs = ArrayList<Rig>()
    private var frameDelayWas: Duration? = null

    @org.junit.After fun tearDown() {
        for (r in rigs) { r.map.worldDone(); r.home.giveBack(); r.room.backdropDone(lowMemory = true) }
        rigs.clear()
        feed(0, 0f, 0f); feed(1, 0f, 0f)
        ShadowLooper.idleMainLooper()
        org.robolectric.shadows.ShadowChoreographer.setPaused(false)
        frameDelayWas?.let { org.robolectric.shadows.ShadowChoreographer.setFrameDelay(it) }
    }
    private fun feed(which: Int, x: Float, y: Float) {
        val k = Class.forName("com.jrees.wowcompanion.PadHub")
        k.getMethod("feedVector", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .invoke(k.getField("INSTANCE").get(null), which, x, y)
    }

    private fun rig(terrain: Boolean, minimapK: Boolean = true, buffs: Boolean = true, zone: Int = 1411): Rig {
        zoneId = zone
        if (frameDelayWas == null) frameDelayWas = org.robolectric.shadows.ShadowChoreographer.getFrameDelay()
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        art(minimapK)
        HomeSettings.init(app)
        Settings.init(app)
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
        for (i in 0 until 300) { Thread.sleep(10); ShadowLooper.idleMainLooper(); if (room.backdropRoot(zone) != null) break }
        settle(4)
        assertNotNull("the art set read (${room.model.maps.keys})", room.backdropRoot(zone))
        map.worldArt = room.homeArt
        map.worldFull = { true }
        map.worldTerrain = { terrain }
        val home = HomeView(act, map, cast, chat) { }
        top.addView(home, FrameLayout.LayoutParams(W, H))
        home.borrow()
        lay(home, W, H)
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        home.onFull(JSONObject().put("type", "full").put("map", JSONObject().put("zone", "Durotar").put("pvp", "contested"))
            .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true))), true)
        map.update(mapDoc(), 0)
        room.update(mapDoc())
        if (buffs) home.onAuras(JSONObject().put("type", "auras").put("now", 100.0)
            .put("b", JSONArray().put(JSONObject().put("id", 1).put("n", "Lightning Shield").put("i", 136051).put("exp", 600.0)))
            .put("d", JSONArray()), true)
        val q = questsDoc()
        map.onQuests(q); home.onQuests(q, true); room.onQuests(q)
        home.tick()
        ShadowLooper.idleMainLooper()
        lay(home, W, H)
        HomeView::class.java.getDeclaredMethod("changed").apply { isAccessible = true }.invoke(home)
        settle(); lay(home, W, H)
        assertTrue(home.openMapModule())
        settle(); lay(home, W, H)
        return Rig(home, map, room).also { rigs.add(it) }
    }

    private var padN = 0
    private fun key(home: HomeView, k: String) { home.auraPad(JSONObject().put("k", k).put("n", ++padN)); ShadowLooper.idleMainLooper() }

    private fun draw(home: HomeView): Bitmap {
        val bm = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm); c.drawColor(Theme.ink)
        home.draw(c)
        return bm
    }
    /** a few frames with the pictures' posts between (the room's reader decodes / makes them off the UI thread) */
    private fun frames(r: Rig, ms: Long = 6_000L): Bitmap {
        val until = System.nanoTime() + ms * 1_000_000L
        var last = -1L; var still = 0
        var bm = draw(r.home)
        while (System.nanoTime() < until) {
            Thread.sleep(40); settle(2); bm = draw(r.home)
            val b = r.room.pictureBytes
            if (b == last && r.room.picturesPending == 0) { if (++still >= 6) break } else still = 0
            last = b
        }
        return bm
    }
    private fun shot(name: String, bm: Bitmap) {
        val d = out ?: return
        d.mkdirs()
        File(d, "$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun mf(n: String) = MapView::class.java.getDeclaredField(n).apply { isAccessible = true }
    /** Home's view: [zoom] (zone widths) with its middle at ([u], [v]) of Durotar's 0..1 (the sticks' wheel taken first) */
    private fun homeView(r: Rig, zoom: Float, u: Float, v: Float) {
        key(r.home, "PADLSHOULDER")
        mf("zoom").setFloat(r.map, zoom); mf("autoZoomCur").setFloat(r.map, zoom)
        mf("panX").setFloat(r.map, u); mf("panY").setFloat(r.map, v)
        r.map.invalidate()
    }
    private fun st(r: Rig) = r.map.controlsState()

    // ---------------------------------------------------------------- 1. Kalimdor on the Map room

    private fun rf(n: String) = WorldMapRoomView::class.java.getDeclaredField(n).apply { isAccessible = true }

    /** the Map room zoomed in from the world's middle toward Durotar with the stick (zoom x1.03 a frame, the view easing to the cursor) */
    @Test fun theMapRoomZoomsIntoKalimdorNotEasternKingdomsSea() {
        val r = rig(terrain = true)
        val room = r.room
        room.visibility = View.VISIBLE
        room.layout(0, 0, W, H)
        room.onShown()
        val jump = WorldMapRoomView::class.java.getDeclaredMethod("jumpTo", Int::class.javaPrimitiveType).apply { isAccessible = true }
        val after = WorldMapRoomView::class.java.getDeclaredMethod("afterZoom").apply { isAccessible = true }
        jump.invoke(room, 947)
        val dur = room.model.placeOn(1411, 947)!!
        rf("zoom").setFloat(room, 1f); rf("panX").setFloat(room, 0.5f); rf("panY").setFloat(room, 0.5f)
        rf("ccx").setFloat(room, (dur[0] + dur[2]) / 2f); rf("ccy").setFloat(room, (dur[1] + dur[3]) / 2f)
        val seen = LinkedHashSet<Int>()
        for (i in 0 until 160) {
            if (rf("canvas").getInt(room) == 1414 && rf("zoom").getFloat(room) > 6f) break     // well in on Kalimdor
            rf("zoom").setFloat(room, rf("zoom").getFloat(room) * 1.03f)
            val px = rf("panX").getFloat(room); val py = rf("panY").getFloat(room)
            rf("panX").setFloat(room, px + (rf("ccx").getFloat(room) - px) * 0.05f)
            rf("panY").setFloat(room, py + (rf("ccy").getFloat(room) - py) * 0.05f)
            after.invoke(room)
            seen.add(rf("canvas").getInt(room))
        }
        assertFalse("never Eastern Kingdoms' canvas on the way to Durotar ($seen)", 1415 in seen)
        assertEquals("the canvas is Kalimdor's", 1414, rf("canvas").getInt(room))
        assertTrue("... zoomed well in (${rf("zoom").getFloat(room)})", rf("zoom").getFloat(room) > 4f)
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        room.layout(0, 0, W, H)
        for (i in 0 until 60) { bm.eraseColor(Color.BLACK); room.layout(0, 0, W, H); room.draw(Canvas(bm)); Thread.sleep(40); settle(2) }
        if (realWorld == null) {
            // the view's middle is Kalimdor's own picture (green), never Eastern Kingdoms' (magenta)
            val p = bm.getPixel(W / 2, H / 2)
            assertNotEquals("Eastern Kingdoms' picture under Durotar", EK_MAGENTA, p)
        }
        shot("room_kalimdor_zoomed", bm)
        room.onHidden()
    }

    @Test fun theWorldsAimIsTheBoxThatHoldsTheCursorThenTheNearest() {
        val r = rig(terrain = true)
        val m = r.room.model
        val dur = m.placeOn(1411, 947)!!
        val d = floatArrayOf((dur[0] + dur[2]) / 2f, (dur[1] + dur[3]) / 2f)
        // the view's middle in Eastern Kingdoms' FRAME (0.37..) but the cursor on Durotar: Kalimdor
        assertEquals(1414, m.aimContinent(947, listOf(d, floatArrayOf(0.5f, 0.5f))))
        // the sea between the boxes, nearer Kalimdor's middle: Kalimdor; nearer Eastern Kingdoms': Eastern Kingdoms
        assertEquals(1414, m.aimContinent(947, listOf(floatArrayOf(0.44f, 0.5f))))
        assertEquals(1415, m.aimContinent(947, listOf(floatArrayOf(0.5f, 0.5f))))
        assertNull("a continent has no continents to aim at", m.aimContinent(1414, listOf(d)))
    }

    // ---------------------------------------------------------------- 1. Kalimdor on Home's map (Painted)

    /** Home in Durotar, Painted, zoomed out to 0.9 with the view's middle just east of Kalimdor's box (Eastern Kingdoms' frame covers it) */
    @Test fun homesPaintedZoomOutEastOfKalimdorStaysOnKalimdor() {
        val r = rig(terrain = false)
        key(r.home, "PADDRIGHT")                                 // the map controls
        // the middle at world u ~0.45 (the list strip shifts the view left of the pan: 0.47 asked), Durotar spanning 0.238..0.356
        val dur = r.room.model.placeOn(1411, 947)!!
        val u = (0.47f - dur[0]) / (dur[2] - dur[0]); val v = 0.47f
        homeView(r, 0.9f, u, v)
        val bm = frames(r)
        assertTrue("the painted art is drawn (${st(r).toList()})", st(r)[4] == 1f && st(r)[6] == 0f)
        assertEquals("drawn on Kalimdor's canvas, not Eastern Kingdoms'", 1414, r.room.backdropCanvas)
        shot("home_kalimdor_painted_east_coast", bm)
        // nearer in toward Durotar: still Kalimdor
        homeView(r, 0.95f, 0.5f, 0.5f)
        frames(r, 2000L)
        assertEquals(1414, r.room.backdropCanvas)
    }

    /**
     * Home in Elwynn (Eastern Kingdoms), Painted, the view moved over Kalimdor's east coast (Azshara, world u 0.39): Home's backdrop began on
     * Eastern Kingdoms' canvas and stayed there (its frame still covered the view), so Kalimdor's coast was Eastern Kingdoms' west sea
     */
    @Test fun homesPaintedViewOverKalimdorFromEasternKingdomsDrawsKalimdor() {
        val r = rig(terrain = false, zone = 1429)
        key(r.home, "PADDRIGHT")
        val ew = r.room.model.placeOn(1429, 947)!!
        val zw = ew[2] - ew[0]; val zh = ew[3] - ew[1]
        val zoom = zw / 0.2f                                     // 0.2 of the world's width across the field
        val halfX = 0.5f / zoom
        // the view's middle (the list strip moves it left of the pan by a sixth of the field) at world (0.39, 0.37)
        val u = (0.39f - ew[0]) / zw + (207f / 1240f) * 2f * halfX
        val v = (0.37f - ew[1]) / zh
        homeView(r, zoom, u, v)
        val bm = frames(r)
        assertTrue("the painted art is drawn (${st(r).toList()})", st(r)[4] == 1f)
        assertNotEquals("not Eastern Kingdoms' canvas over Kalimdor", 1415, r.room.backdropCanvas)
        if (realWorld == null) {
            var ek = 0
            for (y in 0 until H step 4) for (x in 0 until W step 4) if (bm.getPixel(x, y) == EK_MAGENTA) ek++
            assertEquals("Kalimdor's coast is not Eastern Kingdoms' picture", 0, ek)
        }
        shot("home_ek_player_over_kalimdor_painted", bm)
    }

    // ---------------------------------------------------------------- 2. Home's terrain zoom-out (the default)

    @Test fun terrainIsTheDefaultSetting() {
        Settings.init(app)
        assertTrue("Map style when zoomed out: Terrain unless set", Settings.homeMapTerrain)
    }

    @Test fun homesTerrainZoomOutKeepsTheTerrainNeverThePaintedArt() {
        val r = rig(terrain = true)
        key(r.home, "PADDRIGHT")
        val dur = r.room.model.placeOn(1411, 947)!!
        homeView(r, 0.4f, 0.5f, 0.5f)
        val bm = frames(r)
        assertTrue("terrain drawn zoomed out (${st(r).toList()})", st(r)[6] == 1f)
        assertEquals("the painted art never asked (no backdrop painted)", 0, r.room.backdropCanvas)
        assertTrue("the terrain's pictures are in the room's one cache", r.room.pictureBytes > 0L)
        shot("home_terrain_zoom_0.4", bm)
        // the whole world
        for (i in 0 until 10) key(r.home, "PADLSHOULDER")
        val world = frames(r)
        assertEquals("down to the floor", st(r)[3], st(r)[2], 0.001f)
        assertTrue(st(r)[6] == 1f)
        assertEquals(0, r.room.backdropCanvas)
        // fix/map-other-continent (review): a land with no terrain that is neither under the view nor the player's (Zephras Isle: no
        // minimap at all) is not painted on the world zoom-out (the painted pictures stay unread there)
        assertEquals("no land painted (${r.map.paintedConts})", 0, r.map.paintedConts)
        shot("home_terrain_world", world)
        // back in to the zone: let go at once
        for (i in 0 until 12) key(r.home, "PADRSHOULDER")
        frames(r, 1500L)
        assertTrue(st(r)[2] >= 1f)
        assertEquals("freed on zoom-in", 0L, r.room.pictureBytes)
        assertFalse(r.room.backdropInUse)
        if (realArt == null) {
            // Kalimdor's terrain where Kalimdor has a tile; the hole (40, 31) is sea, never Eastern Kingdoms' red tile of the same number
            homeView(r, 0.6f, 0.5f, 0.5f)
            val bm2 = frames(r)
            val k = TerrainOverview.make(1, 1, HOLE.first / 2, HOLE.second / 2)!!
            val q = TerrainOverview.TILE / 2
            val hp = k.getPixel((HOLE.first % 2) * q + q / 2, (HOLE.second % 2) * q + q / 2)
            assertNotEquals("Kalimdor's missing ADT is not Eastern Kingdoms' tile", TILE_EK, hp)
            // (water depth, 2026-11-17: the sea is repainted by its distance to land: a teal-to-navy water colour, blue over red)
            val near = Color.blue(hp) > Color.red(hp) + 30 && Color.green(hp) > Color.red(hp) + 20
            assertTrue("... it is the sea (${Integer.toHexString(hp)})", near)
            var red = 0
            for (y in 0 until H step 4) for (x in 0 until W step 4) if (bm2.getPixel(x, y) == TILE_EK) red++
            // Eastern Kingdoms' tiles sit at their own place on the world, far east of the view round Durotar
            assertEquals("no Eastern Kingdoms tile drawn round Durotar", 0, red)
            assertTrue(dur[2] > dur[0])
        }
    }

    @Test fun aContinentWithoutTerrainFallsBackToItsOwnPaintedArt() {
        val r = rig(terrain = true, minimapK = false)
        key(r.home, "PADDRIGHT")
        homeView(r, 0.5f, 0.5f, 0.5f)
        val bm = frames(r)
        assertTrue("no Kalimdor tiles: the painted art (${st(r).toList()})", st(r)[4] == 1f && st(r)[6] == 0f)
        assertEquals("... Kalimdor's own", 1414, r.room.backdropCanvas)
        if (realWorld == null) assertNotEquals("never Eastern Kingdoms' picture", EK_MAGENTA, bm.getPixel(W * 3 / 4, H / 2))
        shot("home_kalimdor_no_terrain_fallback", bm)
    }

    @Test fun overviewLevelsFollowTheScreen() {
        assertEquals(1, TerrainOverview.level(200f))
        assertEquals(1, TerrainOverview.level(128f))
        assertEquals(2, TerrainOverview.level(64f))
        assertEquals(4, TerrainOverview.level(12f))
        assertEquals(TerrainOverview.LEVEL_MAX, TerrainOverview.level(1f))
    }

    // ---------------------------------------------------------------- 3. the buffs page: no quest cursor

    @Test fun theBuffsPageShowsNoQuestCursorAndTheQuestsComeBackWhereTheyWere() {
        val r = rig(terrain = true)
        key(r.home, "PADDDOWN")                                  // the list's cursor shows (a press on the list)
        draw(r.home)
        assertTrue("the quests page: the list's cursor on its row", r.map.listSelDrawn)
        key(r.home, "PADDRIGHT"); key(r.home, "PADDRIGHT")
        assertEquals(HomeMapPages.Page.BUFFS, r.home.mapPageNow())
        val bm = frames(r)
        assertFalse("the buffs page: no list cursor", r.map.listSelDrawn)
        assertEquals("... and the list's hints are not shown", "buffs", r.map.hintsDrawn)
        shot("home_buffs_no_quest_cursor", bm)
        key(r.home, "PADDLEFT"); key(r.home, "PADDLEFT")
        assertEquals(HomeMapPages.Page.QUESTS, r.home.mapPageNow())
        draw(r.home)
        assertTrue("back on the quests: the cursor where it was", r.map.listSelDrawn)
        assertEquals("list", r.map.hintsDrawn)
    }

    // ---------------------------------------------------------------- 4. the controls page's compact list

    private fun ease(r: Rig, ms: Int) { for (t in 0 until ms step 16) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper(); draw(r.home) } }

    @Test fun theControlsPageEasesTheListCompactAndBack() {
        val r = rig(terrain = true)
        draw(r.home)
        val fullW = mf("lastTrackPx").getFloat(r.map)
        assertEquals("the quests page: full cards", 0f, r.map.listCompactK(), 0f)
        shot("home_quests_full_list", frames(r))
        key(r.home, "PADDRIGHT")
        ease(r, 96)
        val mid = r.map.listCompactK()
        assertTrue("it eases, not a snap ($mid)", mid > 0f && mid < 1f)
        ease(r, 200)
        assertEquals("the controls page: compact", 1f, r.map.listCompactK(), 0f)
        val compactW = mf("lastTrackPx").getFloat(r.map)
        assertTrue("the map takes the width ($fullW -> $compactW)", compactW < fullW * 0.8f)
        shot("home_controls_compact_list", frames(r))
        key(r.home, "PADDRIGHT")                                 // the buffs: full again
        ease(r, 300)
        assertEquals(0f, r.map.listCompactK(), 0f)
        key(r.home, "PADDLEFT"); ease(r, 300)
        assertEquals(1f, r.map.listCompactK(), 0f)
        key(r.home, "PADDLEFT"); ease(r, 300)
        assertEquals("back on the quests: full", 0f, r.map.listCompactK(), 0f)
        assertEquals(fullW, mf("lastTrackPx").getFloat(r.map), 0.5f)
    }

    // ---------------------------------------------------------------- 5. untracked quests: full grey discs, tappable, filterable

    @Test fun untrackedQuestsAreFullGreyDiscsThatOpenTheirPanelAndFilterOut() {
        val r = rig(terrain = true)
        r.map.onFocusQuest = { }                                 // the host's focus (CompanionHost): the discs are tappable with it
        r.map.update(mapDoc(), 0)                                // the next map document, the tracker known (as on the device)
        draw(r.home)
        assertTrue("the untracked quest's disc is drawn (${r.map.untrackedDrawn})", r.map.untrackedDrawn >= 1)
        shot("home_untracked_discs_shown", frames(r))
        // A on it with the controls' cursor: its panel (focus needs it tracked)
        key(r.home, "PADDRIGHT")
        mf("curU").setFloat(r.map, 0.47f); mf("curV").setFloat(r.map, 0.50f)
        draw(r.home)
        key(r.home, "PAD1")
        assertTrue("A on the untracked disc opens its panel", r.map.panelOpen())
        key(r.home, "PAD2")
        // the filter: Untracked quest objectives off hides them
        val layers = mf("layers").get(r.map) as QuestLayers
        assertEquals("Untracked quest objectives", QuestLayers.Layer.OTHER.label)
        layers.toggle(QuestLayers.Layer.OTHER)
        try {
            r.map.update(mapDoc(), 0)
            val bm = frames(r)
            assertEquals("filtered out", 0, r.map.untrackedDrawn)
            shot("home_untracked_discs_filtered", bm)
        } finally { layers.toggle(QuestLayers.Layer.OTHER) }
    }

    /** the user's pick (2026-11-17, "Grey there too"): the Map room draws untracked quests as Home's grey discs, filtered by the same switch */
    @Test fun theMapRoomDrawsUntrackedQuestsGreyAndFiltersThem() {
        val r = rig(terrain = true)
        val room = r.room
        room.visibility = View.VISIBLE
        room.layout(0, 0, W, H)
        room.onShown()
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        fun roomFrames() { for (i in 0 until 40) { bm.eraseColor(Color.BLACK); room.layout(0, 0, W, H); room.draw(Canvas(bm)); Thread.sleep(30); settle(2) } }
        roomFrames()
        assertEquals("the untracked quest grey, the tracked one not", listOf(8), room.greyQuests())
        shot("room_untracked_grey", bm)
        val prefs = app.getSharedPreferences("mapfilters", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(QuestLayers.Layer.OTHER.key, false).commit()
        try {
            roomFrames()
            assertEquals("Untracked quest objectives off: none", emptyList<Int>(), room.greyQuests())
            shot("room_untracked_filtered", bm)
        } finally { prefs.edit().remove(QuestLayers.Layer.OTHER.key).commit() }
        room.onHidden()
    }

    // ---------------------------------------------------------------- the numbers (real art only)

    @Test fun terrainMemoryFootprint() {
        if (realArt == null || realWorld == null) return
        val report = StringBuilder()
        fun note(l: String) { println("MK-MEM $l"); report.append(l).append('\n') }
        fun mb(b: Long) = "%.1f MB".format(b / 1048576.0)
        val r = rig(terrain = true)
        key(r.home, "PADDRIGHT")
        frames(r, 2000L)
        note("Terrain style, Home in Durotar. zone (zoom ${"%.2f".format(st(r)[2])}): ${mb(r.room.pictureBytes)} in the room's cache")
        var peak = 0L
        for (i in 0 until 12) {
            key(r.home, "PADLSHOULDER")
            val t0 = System.nanoTime()
            frames(r, 20_000L)
            peak = maxOf(peak, r.room.pictureBytes)
            note("zoom ${"%.3f".format(st(r)[2])} (floor ${"%.3f".format(st(r)[3])}): ${mb(r.room.pictureBytes)} held, terrain ${st(r)[6] == 1f}, " +
                "painted ${r.room.backdropCanvas != 0}, ${(System.nanoTime() - t0) / 1_000_000} ms to settle")
            if (i == 11) shot("home_terrain_world_real", draw(r.home))
        }
        note("peak zoomed out: ${mb(peak)}; the cache's cap is 72 MB (WorldMapRoomView's LruCache, shared with the Map room)")
        for (i in 0 until 12) key(r.home, "PADRSHOULDER")
        frames(r, 1500L)
        note("back on the zone: ${mb(r.room.pictureBytes)} held (freed on zoom-in)")
        for (i in 0 until 12) key(r.home, "PADLSHOULDER")
        val t1 = System.nanoTime()
        frames(r, 20_000L)
        note("out to the world again: ${mb(r.room.pictureBytes)} (read back from the disk copies in ${(System.nanoTime() - t1) / 1_000_000} ms)")
        val disk = TerrainOverview.dir?.let { File(it, "terrain-ov") }?.walkTopDown()?.filter { it.isFile }?.toList() ?: emptyList()
        note("disk copies: ${disk.size} pictures, ${mb(disk.sumOf { it.length() })} (the app's cache folder, one minimap build)")
        key(r.home, "PAD2"); key(r.home, "PAD2"); settle()
        note("module closed: ${mb(r.room.pictureBytes)} held; in use ${r.room.backdropInUse}")
        ShadowSystemClock.advanceBy(Duration.ofMillis(MapRoomLetGo.AFTER_MS + 1000L)); ShadowLooper.idleMainLooper()
        note("61 s after the close: ${mb(r.room.pictureBytes)}")
        out?.let { d -> d.mkdirs(); File(d, "memory.txt").writeText(report.toString()) }
    }
}
