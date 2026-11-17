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
 * ZOOMED OUT, THEN IN ON THE OTHER CONTINENT (the user, 2026-11-17, on test build 1484: "when zooming out and then zooming in on the other
 * continent the screen goes black"). Home's map drew the Map room's art / the terrain only while zoomed out past the player's zone; at the
 * zone's width and closer it drew only the PLAYER's continent's close minimap tiles through the player's zone rect, so over the other
 * continent the field was black (Terrain and Painted alike, both ways). And a continent with no terrain built on the phone was bare sea
 * even zoomed out. Now, per continent under the view: its terrain (and its own close tiles) if built, else its painted picture and zone art,
 * else "Map not built yet"; the player's tiles never land on it; its pictures are freed only back on the player's own continent.
 * Made-up art (Kalimdor green / its tiles bright green, Eastern Kingdoms magenta / its tiles red) unless MK_WORLD_ROOT and MK_ART_ROOT
 * are set; with MK_SHOTS_DIR each case is drawn there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MapOtherContinentShotsTest {
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
        val root = File("build/tmp/moc-art-${if (realWorld != null) "r" else "m"}-${if (minimapK) "k" else "nok"}").absoluteFile
        if (realWorld != null) {
            for (d in listOf("worldmap", "seamless")) if (!File(root, d).isDirectory) File(realWorld, d).takeIf { it.isDirectory }?.copyRecursively(File(root, d))
        } else madeUpWorld(File(root, "worldmap"))
        if (realArt != null) {
            for (d in listOf("ui", "fonts", "minimap")) if (!File(root, d).isDirectory) File(realArt, d).takeIf { it.isDirectory }?.copyRecursively(File(root, d))
        } else madeUpMinimap(File(root, "minimap"))
        if (!minimapK) File(root, "minimap/1").deleteRecursively()
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, root)
        MinimapTiles.refresh(); TerrainOverview.refresh(); TerrainOverview.load()
        TerrainOverview.dir = File("build/tmp/moc-cache-${if (realWorld != null) "r" else "m"}-${if (minimapK) "k" else "nok"}").absoluteFile
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
        // Eastern Kingdoms' over the same numbers as Kalimdor's (a tile drawn by the wrong continent shows red on Durotar), and round Elwynn
        for (col in 26..50) for (row in 25..53) if (row <= 40 || col <= 37) { solid(File(dir, "0/${col}_$row.webp"), TILE_EK); e.put(JSONArray(listOf(col, row))) }
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

    // ---------------------------------------------------------------- zoom out, then in on the OTHER continent
    private fun fieldStats(r: Rig, bm: Bitmap): IntArray {
        // [dark, kalGreen, tileK, ekMagenta, tileEk, other] over the map's field right of the quest list
        val loc = IntArray(2); r.map.getLocationInWindow(loc)
        val x0 = loc[0]; val y0 = loc[1]; val w = r.map.width; val h = r.map.height
        val s = IntArray(6)
        for (y in y0 + 10 until y0 + h - 10 step 6) for (x in x0 + w / 3 until x0 + w - 10 step 6) {
            if (x !in 0 until bm.width || y !in 0 until bm.height) continue
            val p = bm.getPixel(x, y)
            val mx = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            when {
                p == KAL_GREEN -> s[1]++; p == TILE_K -> s[2]++; p == EK_MAGENTA -> s[3]++; p == TILE_EK -> s[4]++
                mx < 32 -> s[0]++; else -> s[5]++
            }
        }
        return s
    }
    private class Seen(val r: Rig, val dark: Int, val s: IntArray)
    /** player in [zone]; zoomed out to the world, then the view over [target]'s middle at [zoomTo] (the player's zone widths) */
    private fun over(name: String, terrain: Boolean, minimapK: Boolean, zone: Int, target: Int, zoomTo: Float, rig: Rig? = null): Seen {
        val r = rig ?: rig(terrain = terrain, minimapK = minimapK, zone = zone)
        key(r.home, "PADDRIGHT")
        for (i in 0 until 10) key(r.home, "PADLSHOULDER")
        frames(r, 2000L)
        val zp = r.room.model.placeOn(zone, 947)!!; val tp = r.room.model.placeOn(target, 947)!!
        val zw = zp[2] - zp[0]; val zh = zp[3] - zp[1]
        homeView(r, zoomTo, ((tp[0] + tp[2]) / 2f - zp[0]) / zw, ((tp[1] + tp[3]) / 2f - zp[1]) / zh)
        val bm = frames(r, 3000L)
        val s = fieldStats(r, bm)
        val dark = 100 * s[0] / s.sum().coerceAtLeast(1)
        println("MOC $name zoom=${st(r)[2]} worldDrew=${st(r)[4]} terrainDrew=${st(r)[6]} other=${r.map.otherContDrawn} painted=${r.map.paintedConts} " +
            "otherTiles=${r.map.otherTiles} canvas=${r.room.backdropCanvas} dark=$dark% kalGreen=${s[1]} tileK=${s[2]} ekMag=${s[3]} tileEk=${s[4]}")
        shot("moc_$name", bm)
        return Seen(r, dark, s)
    }
    private fun assertDrawn(name: String, v: Seen) {
        assertTrue("$name: the view is over the other continent", v.r.map.otherContDrawn)
        assertEquals("$name: its art drew", 1f, st(v.r)[4])
        assertTrue("$name: not a black field (${v.dark}% dark)", v.dark < 50)
        assertNull("$name: no note", mf("artNote").get(v.r.map))
    }

    @Test fun eastern_kingdoms_player_zooms_in_on_durotar_terrain() {
        for (z in listOf(1f, 3f)) {
            val v = over("ek_terrain_z$z", true, true, 1429, 1411, z)
            assertDrawn("terrain z$z", v)
            assertEquals("terrain z$z: the terrain drew", 1f, st(v.r)[6])
            assertEquals("terrain z$z: no Eastern Kingdoms tile on Kalimdor", 0, v.s[4])
            if (realArt == null) assertTrue("terrain z$z: Kalimdor's own tiles", v.s[2] > 0 && v.r.map.otherTiles > 0)
            tearDown()
        }
    }

    @Test fun eastern_kingdoms_player_zooms_in_on_durotar_painted() {
        val v = over("ek_painted_z1", false, true, 1429, 1411, 1f)
        assertDrawn("painted", v)
        assertEquals("painted: Kalimdor's own canvas", 1414, v.r.room.backdropCanvas)
        assertEquals("painted: no Eastern Kingdoms tile on Kalimdor", 0, v.s[4])
    }

    @Test fun kalimdor_with_no_terrain_built_is_its_painted_picture() {
        for (z in listOf(0.5f, 1f)) {
            val v = over("ek_terrain_nok_z$z", true, false, 1429, 1411, z)
            assertDrawn("no Kalimdor terrain z$z", v)
            assertTrue("no Kalimdor terrain z$z: painted in its box", v.r.map.paintedConts > 0)
            assertEquals("no Kalimdor terrain z$z: Kalimdor's canvas", 1414, v.r.room.backdropCanvas)
            assertEquals("no Kalimdor terrain z$z: no Eastern Kingdoms tile", 0, v.s[4])
            tearDown()
        }
    }

    @Test fun kalimdor_player_zooms_in_on_elwynn() {
        val t = over("k_terrain_z1", true, true, 1411, 1429, 1f)
        assertDrawn("Kalimdor player, terrain", t)
        assertEquals("Kalimdor player, terrain: no Kalimdor tile on Eastern Kingdoms", 0, t.s[2])
        tearDown()
        val p = over("k_painted_z1", false, true, 1411, 1429, 1f)
        assertDrawn("Kalimdor player, painted", p)
        assertEquals("Kalimdor player, painted: Eastern Kingdoms' canvas", 1415, p.r.room.backdropCanvas)
        assertEquals("Kalimdor player, painted: no Kalimdor tile", 0, p.s[2])
    }

    @Test fun pictures_are_freed_only_back_on_the_players_continent() {
        val v = over("free_other", true, true, 1429, 1411, 1f)
        assertTrue("over Kalimdor at the zone's width the art is still asked for", v.r.room.backdropInUse)
        // back over Elwynn (the player's own zone) at its width: free on zoom-in
        homeView(v.r, 1f, 0.5f, 0.5f)
        val bm = frames(v.r, 2000L)
        shot("moc_free_home", bm)
        assertFalse("back on the player's continent", v.r.map.otherContDrawn)
        assertFalse("back on the player's continent the pictures go", v.r.room.backdropInUse)
    }

    @Test fun no_art_at_all_says_map_not_built_yet() {
        val r = rig(terrain = true, minimapK = true, zone = 1429)
        val real = r.room.homeArt
        // the room's art reads continents, but has nothing to paint (not built, or paused by Android's warning)
        r.map.worldArt = object : MapView.WorldArt {
            override fun paint(c: Canvas, zone: Int, zr: android.graphics.RectF, clip: android.graphics.RectF, client: View) = false
            override fun root(zone: Int) = real.root(zone)
            override fun done() = real.done()
            override fun release() = real.release()
            override fun aim(zone: Int, at: List<FloatArray>) = real.aim(zone, at)
        }
        val v = over("no_art", true, true, 1429, 1411, 1f, rig = r)
        assertTrue(v.r.map.otherContDrawn)
        assertEquals(HomeMapNote.NOT_BUILT, mf("artNote").get(v.r.map))
        assertTrue(HomeMapNote.otherShows(false, true, false))
        assertFalse(HomeMapNote.otherShows(true, true, false))
        assertFalse(HomeMapNote.otherShows(false, false, false))
        assertFalse(HomeMapNote.otherShows(false, true, true))
    }
}
