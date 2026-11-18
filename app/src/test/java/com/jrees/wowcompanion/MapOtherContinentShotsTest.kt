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
    /** Zephras Isle (UiMap 2521 on map 2991, straight on the world; MapSkybornShotsTest's rect): no minimap, so no terrain, ever */
    private val ISLE = doubleArrayOf(2991.0, 4956.25, 1247.917, 4231.25, -1331.25)
    private val ISLE_GOLD = Color.rgb(230, 190, 40)
    /** the made-up minimap: Kalimdor's ADTs round Durotar, but NOT (40, 31); Eastern Kingdoms' over the same numbers, (40, 31) too */
    private val HOLE = 40 to 31

    // ---------------------------------------------------------------- the art
    private fun art(minimapK: Boolean = true) {
        val root = File("build/tmp/moc-art2-${if (realWorld != null) "r" else "m"}-${if (minimapK) "k" else "nok"}").absoluteFile
        if (realWorld != null) {
            for (d in listOf("worldmap", "seamless")) if (!File(root, d).isDirectory) File(realWorld, d).takeIf { it.isDirectory }?.copyRecursively(File(root, d))
        } else madeUpWorld(File(root, "worldmap"))
        if (realArt != null) {
            for (d in listOf("ui", "fonts", "minimap")) if (!File(root, d).isDirectory) File(realArt, d).takeIf { it.isDirectory }?.copyRecursively(File(root, d))
        } else madeUpMinimap(File(root, "minimap"))
        if (!minimapK) File(root, "minimap/1").deleteRecursively()
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, root)
        MinimapTiles.refresh(); TerrainOverview.refresh(); TerrainOverview.load()
        TerrainOverview.dir = File("build/tmp/moc-cache2-${if (realWorld != null) "r" else "m"}-${if (minimapK) "k" else "nok"}").absoluteFile
    }

    private fun madeUpWorld(dir: File) {
        if (File(dir, "manifest.json").isFile) return
        dir.mkdirs()
        val maps = listOf(
            Triple(947, "Azeroth", JSONObject().put("mapType", 1).put("parent", 0).put("rect", JSONArray(listOf(1, 14824.5254, -19900.1797, 8723.958, -12087.4941)))),
            Triple(1414, "Kalimdor", JSONObject().put("mapType", 2).put("parent", 947).put("rect", JSONArray(listOf(1, 12799.9004, -11733.2998, 17066.5996, -19733.2109)))),
            Triple(1415, "Eastern Kingdoms", JSONObject().put("mapType", 2).put("parent", 947).put("rect", JSONArray(listOf(0, 7466.6001, -16000, 16000, -19199.9004)))),
            Triple(1411, "Durotar", JSONObject().put("mapType", 3).put("parent", 1414).put("rect", JSONArray(DUROTAR.toList()))),
            Triple(1429, "Elwynn Forest", JSONObject().put("mapType", 3).put("parent", 1415).put("rect", JSONArray(listOf(0, -7939.583, -10254.166, 1535.4166, -1935.4166)))),
            Triple(2521, "Zephras Isle", JSONObject().put("mapType", 3).put("parent", 947).put("rect", JSONArray(ISLE.toList()))))
        val man = JSONObject()
        for ((id, name, o) in maps) {
            man.put("$id", JSONObject().put("name", name).put("mapType", o.getInt("mapType")).put("parent", o.getInt("parent")).put("overlays", 0))
            val d = File(dir, "$id").apply { mkdirs() }
            File(d, "index.json").writeText(o.put("uiMapID", id).put("name", name).put("w", 1002).put("h", 668).put("overlays", JSONArray()).toString())
            val b = Bitmap.createBitmap(501, 334, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val (a, z) = when (id) { 947 -> Color.rgb(40, 70, 110) to Color.rgb(30, 50, 90); 1414 -> KAL_GREEN to KAL_GREEN
                1415 -> EK_MAGENTA to EK_MAGENTA; 2521 -> ISLE_GOLD to ISLE_GOLD; else -> Color.rgb(150, 120, 70) to Color.rgb(110, 90, 50) }
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
        val (name, r) = when (zoneId) { 1429 -> "Elwynn Forest" to ELWYNN; 2521 -> "Zephras Isle" to ISLE; else -> "Durotar" to DUROTAR }
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
        // [dark, kalGreen, tileK, ekMagenta, tileEk, other, isleGold] over the map's field right of the quest list
        val loc = IntArray(2); r.map.getLocationInWindow(loc)
        val x0 = loc[0]; val y0 = loc[1]; val w = r.map.width; val h = r.map.height
        val s = IntArray(7)
        for (y in y0 + 10 until y0 + h - 10 step 6) for (x in x0 + w / 3 until x0 + w - 10 step 6) {
            if (x !in 0 until bm.width || y !in 0 until bm.height) continue
            val p = bm.getPixel(x, y)
            val mx = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            when {
                p == KAL_GREEN -> s[1]++; p == TILE_K -> s[2]++; p == EK_MAGENTA -> s[3]++; p == TILE_EK -> s[4]++; p == ISLE_GOLD -> s[6]++
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
        mf("curU").setFloat(r.map, mf("panX").getFloat(r.map)); mf("curV").setFloat(r.map, mf("panY").getFloat(r.map))
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
        // zoomed out (Painted's own range): Kalimdor's painted canvas
        val o = over("ek_painted_z0.9", false, true, 1429, 1411, 0.9f)
        assertDrawn("painted z0.9", o)
        assertEquals("painted z0.9: Kalimdor's own canvas", 1414, o.r.room.backdropCanvas)
        assertEquals("painted z0.9: no Eastern Kingdoms tile on Kalimdor", 0, o.s[4])
        tearDown()
        // at the zone's width and closer the style is over (as on the player's own continent): Kalimdor's terrain and its own close
        // tiles (review: Painted stayed on the upscaled painting there)
        val v = over("ek_painted_z1", false, true, 1429, 1411, 1f)
        assertDrawn("painted", v)
        assertEquals("painted z1: Kalimdor's terrain", 1f, st(v.r)[6])
        if (realArt == null) assertTrue("painted z1: Kalimdor's own tiles", v.s[2] > 0 && v.r.map.otherTiles > 0)
        assertEquals("painted: no Eastern Kingdoms tile on Kalimdor", 0, v.s[4])
    }

    @Test fun kalimdor_with_no_terrain_built_is_its_painted_picture() {
        for (z in listOf(0.5f, 1f)) {
            val v = over("ek_terrain_nok_z$z", true, false, 1429, 1411, z)
            assertDrawn("no Kalimdor terrain z$z", v)
            assertTrue("no Kalimdor terrain z$z: painted in its box", v.r.map.paintedConts > 0)
            assertEquals("no Kalimdor terrain z$z: Kalimdor's canvas", 1414, v.r.room.backdropCanvas)
            assertEquals("no Kalimdor terrain z$z: no Eastern Kingdoms tile", 0, v.s[4])
            // (review 2: the painting blown up to a zone's width showed its title's letters and sea hatching as blocks: there it is
            // faded into its smooth copy, the zones' art over it)
            if (z >= 1f) assertTrue("no Kalimdor terrain z$z: Kalimdor's painting softened", softened(v.r.room, 1414))
            tearDown()
        }
    }

    @Test fun kalimdor_player_zooms_in_on_elwynn() {
        val t = over("k_terrain_z1", true, true, 1411, 1429, 1f)
        assertDrawn("Kalimdor player, terrain", t)
        assertEquals("Kalimdor player, terrain: no Kalimdor tile on Eastern Kingdoms", 0, t.s[2])
        tearDown()
        val o = over("k_painted_z0.9", false, true, 1411, 1429, 0.9f)
        assertDrawn("Kalimdor player, painted z0.9", o)
        assertEquals("Kalimdor player, painted z0.9: Eastern Kingdoms' canvas", 1415, o.r.room.backdropCanvas)
        assertEquals("Kalimdor player, painted z0.9: no Kalimdor tile", 0, o.s[2])
        tearDown()
        val p = over("k_painted_z1", false, true, 1411, 1429, 1f)
        assertDrawn("Kalimdor player, painted", p)
        assertEquals("Kalimdor player, painted z1: Eastern Kingdoms' terrain", 1f, st(p.r)[6])
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

    // ---------------------------------------------------------------- review: the sea between, the island, the Map room

    /** a point of the world (947's 0..1) in no continent's box, on the line between Kalimdor's box and Eastern Kingdoms' */
    private fun seaBetween(r: Rig): FloatArray {
        val m = r.room.model
        val k = m.placeOn(1411, 947)!!; val e = m.placeOn(1429, 947)!!
        val v = (k[1] + k[3]) / 2f
        // the middle of the widest run of open sea on that line
        val sea = (0..400).map { k[0] + (e[2] - k[0]) * it / 400f }.filter { u -> m.aimContinent(947, listOf(floatArrayOf(u, v)), nearest = false) == null }
        if (sea.isEmpty()) throw AssertionError("no sea between the boxes")
        return floatArrayOf((sea.first() + sea.last()) / 2f, v)
    }
    /** Home's view's MIDDLE and the controls' cursor at world point [w] at [zoom] (the quest list moves the middle left of the pan by
     *  a sixth of the field: homesPaintedViewOverKalimdorFromEasternKingdomsDrawsKalimdor's rule) */
    private fun viewAt(r: Rig, zoom: Float, w: FloatArray) {
        val zp = r.room.model.placeOn(zoneId, 947)!!
        val u = (w[0] - zp[0]) / (zp[2] - zp[0]); val v = (w[1] - zp[1]) / (zp[3] - zp[1])
        homeView(r, zoom, u + (207f / 1240f) / zoom, v)
        mf("curU").setFloat(r.map, u); mf("curV").setFloat(r.map, v)
    }

    @Test fun the_sea_between_the_boxes_zoomed_in_is_the_art_and_frees_nothing() {
        for (terrain in listOf(true, false)) {
            val r = rig(terrain = terrain, zone = 1429)
            key(r.home, "PADDRIGHT")
            for (i in 0 until 10) key(r.home, "PADLSHOULDER")
            frames(r, 2000L)
            // in over Durotar first (the other continent), then across to the sea between, at the zone's width
            val tp = r.room.model.placeOn(1411, 947)!!
            viewAt(r, 1f, floatArrayOf((tp[0] + tp[2]) / 2f, (tp[1] + tp[3]) / 2f))
            frames(r, 2000L)
            assertTrue("over Durotar first", r.map.otherContDrawn)
            val bytes = r.room.pictureBytes
            viewAt(r, 1f, seaBetween(r))
            // the first frame on the sea: nothing freed (each crossing re-read every picture)
            draw(r.home)
            assertTrue("not freed on reaching the sea", r.room.backdropInUse)
            val bm = frames(r, 2000L)
            val s = fieldStats(r, bm)
            val dark = 100 * s[0] / s.sum().coerceAtLeast(1)
            println("MOC sea terrain=$terrain worldDrew=${st(r)[4]} terrainDrew=${st(r)[6]} other=${r.map.otherContDrawn} dark=$dark% bytes $bytes -> ${r.room.pictureBytes}")
            shot("moc_sea_${if (terrain) "terrain" else "painted"}_z1", bm)
            assertFalse("the sea is nobody's continent", r.map.otherContDrawn)
            assertEquals("the art drew over the sea", 1f, st(r)[4])
            // (review 2: Terrain's own deep sea filled the whole field there - near-black, the same look as the black screen. Water depth,
            // 2026-11-17: Terrain's sea is WaterDepth's navy-teal now, the coasts' gradients running into it, so in Terrain the sea between
            // is the terrain's - no switch to the painting at the boxes' edges; Painted keeps the painted sea)
            assertEquals("Terrain: the terrain's sea; Painted: the painted sea ($terrain)", if (terrain) 1f else 0f, st(r)[6])
            assertTrue("a sea, not a black field ($terrain, $dark% dark)", dark < 50)
            assertTrue("not freed crossing the sea", r.room.backdropInUse && r.room.pictureBytes > 0L)
            assertNull("no note", mf("artNote").get(r.map))
            tearDown()
        }
    }

    @Test fun an_eastern_kingdoms_player_zooms_in_on_zephras_isle() {
        for (terrain in listOf(true, false)) {
            val v = over("ek_isle_${if (terrain) "terrain" else "painted"}_z1", terrain, true, 1429, 2521, 1f)
            assertDrawn("the island ($terrain)", v)
            if (terrain) assertTrue("Terrain: the island (no terrain ever) is painted in its box", v.r.map.paintedConts > 0)
            if (realWorld == null) assertTrue("the island's own art (${v.s.toList()})", v.s[6] > 0)
            assertEquals("no Eastern Kingdoms tile on the island", 0, v.s[4])
            tearDown()
        }
    }

    @Test fun a_zephras_player_zooms_in_on_elwynn_and_durotar() {
        for (terrain in listOf(true, false)) for (target in listOf(1429, 1411)) {
            val v = over("isle_${target}_${if (terrain) "terrain" else "painted"}_z1", terrain, true, 2521, target, 1f)
            assertDrawn("the island's player over $target ($terrain)", v)
            if (target == 1411) assertEquals("no Eastern Kingdoms tile on Durotar", 0, v.s[4])
            tearDown()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun softened(room: WorldMapRoomView, id: Int) =
        (WorldMapRoomView::class.java.getDeclaredField("soft").apply { isAccessible = true }.get(room) as Map<Int, Bitmap>).containsKey(id)

    /** review 2: zoomed in on Durotar's EAST COAST (mostly sea), Terrain, from Eastern Kingdoms, with and without Kalimdor's terrain:
     *  the sea is a sea, never a dark field (no terrain: the painted Kalimdor, softened, in its box; terrain: its water keyed out) */
    @Test fun the_other_continents_coast_zoomed_in_is_not_a_dark_field() {
        for (k in listOf(false, true)) {
            val r = rig(terrain = true, minimapK = k, zone = 1429)
            key(r.home, "PADDRIGHT")
            for (i in 0 until 10) key(r.home, "PADLSHOULDER")
            frames(r, 2000L)
            val tp = r.room.model.placeOn(1411, 947)!!
            viewAt(r, 1f, floatArrayOf(tp[0] + (tp[2] - tp[0]) * 0.8f, (tp[1] + tp[3]) / 2f))
            val bm = frames(r, 3000L)
            val s = fieldStats(r, bm)
            val dark = 100 * s[0] / s.sum().coerceAtLeast(1)
            println("MOC coast terrainK=$k worldDrew=${st(r)[4]} terrainDrew=${st(r)[6]} other=${r.map.otherContDrawn} painted=${r.map.paintedConts} dark=$dark%")
            shot("moc_coast_${if (k) "k" else "nok"}_terrain_z1", bm)
            assertTrue("over Kalimdor ($k)", r.map.otherContDrawn)
            assertEquals("the art drew ($k)", 1f, st(r)[4])
            assertEquals("no Eastern Kingdoms tile on Kalimdor ($k)", 0, s[4])
            if (!k) assertTrue("no terrain: painted in its box", r.map.paintedConts > 0)
            else assertEquals("terrain: Kalimdor's terrain", 1f, st(r)[6])
            // (with terrain the minimap's own water, near-black, filled 92% of the field: now keyed out over a sea colour)
            assertTrue("the sea half is a sea, not a dark field ($k, $dark% dark)", dark < 25)
            tearDown()
        }
    }

    /** the Map room (no zone-width cut-off, no Home tiles): an Eastern Kingdoms player, out to the world, in on Durotar, back to Elwynn */
    @Test fun the_map_room_zooms_from_eastern_kingdoms_onto_kalimdor_and_back() {
        val r = rig(terrain = true, zone = 1429)
        val room = r.room
        room.visibility = View.VISIBLE
        room.layout(0, 0, W, H)
        room.onShown()
        fun rf(n: String) = WorldMapRoomView::class.java.getDeclaredField(n).apply { isAccessible = true }
        val jump = WorldMapRoomView::class.java.getDeclaredMethod("jumpTo", Int::class.javaPrimitiveType).apply { isAccessible = true }
        val after = WorldMapRoomView::class.java.getDeclaredMethod("afterZoom").apply { isAccessible = true }
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        fun look(name: String): Int {
            for (i in 0 until 40) { bm.eraseColor(Color.BLACK); room.layout(0, 0, W, H); room.draw(Canvas(bm)); Thread.sleep(30); settle(2) }
            shot("moc_room_$name", bm)
            var d = 0; var n = 0
            for (y in 40 until H - 40 step 6) for (x in 40 until W - 40 step 6) {
                val p = bm.getPixel(x, y); n++
                if (maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF) < 32) d++
            }
            return 100 * d / n
        }
        fun glide(to: Int, until: Int) {
            jump.invoke(room, 947)
            val t = room.model.placeOn(to, 947)!!
            rf("zoom").setFloat(room, 1f); rf("panX").setFloat(room, 0.5f); rf("panY").setFloat(room, 0.5f)
            rf("ccx").setFloat(room, (t[0] + t[2]) / 2f); rf("ccy").setFloat(room, (t[1] + t[3]) / 2f)
            for (i in 0 until 200) {
                if (rf("canvas").getInt(room) == until && rf("zoom").getFloat(room) > 6f) break
                rf("zoom").setFloat(room, rf("zoom").getFloat(room) * 1.03f)
                val px = rf("panX").getFloat(room); val py = rf("panY").getFloat(room)
                rf("panX").setFloat(room, px + (rf("ccx").getFloat(room) - px) * 0.05f)
                rf("panY").setFloat(room, py + (rf("ccy").getFloat(room) - py) * 0.05f)
                after.invoke(room)
            }
        }
        glide(1411, 1414)
        assertEquals("in on Durotar: Kalimdor's canvas", 1414, rf("canvas").getInt(room))
        val dk = look("ek_player_on_durotar")
        assertTrue("Kalimdor drawn, not black ($dk% dark)", dk < 50)
        if (realWorld == null) assertNotEquals("not Eastern Kingdoms' picture under Durotar", EK_MAGENTA, bm.getPixel(W / 2, H / 2))
        glide(1429, 1415)
        assertEquals("back in on Elwynn: Eastern Kingdoms' canvas", 1415, rf("canvas").getInt(room))
        val de = look("ek_player_back_on_elwynn")
        assertTrue("Eastern Kingdoms drawn, not black ($de% dark)", de < 50)
        room.onHidden()
    }

    // ---------------------------------------------------------------- water with depth (the user, 2026-11-17: "I dont love it being one
    // shade of teal. Can we give the water depth?"; the pick "Shore-to-deep gradient", every continent, zoomed in and out, Terrain style)

    /** [shallow, deep, dark, all] over the map's field right of the quest list */
    private fun waterStats(r: Rig, bm: Bitmap): IntArray {
        val loc = IntArray(2); r.map.getLocationInWindow(loc)
        val s = IntArray(4)
        for (y in loc[1] + 10 until loc[1] + r.map.height - 10 step 5) for (x in loc[0] + r.map.width / 3 until loc[0] + r.map.width - 10 step 5) {
            if (x !in 0 until bm.width || y !in 0 until bm.height) continue
            val p = bm.getPixel(x, y)
            val R = Color.red(p); val G = Color.green(p); val B = Color.blue(p)
            if (G >= 130 && B >= 120 && R < G - 30) s[0]++
            if (kotlin.math.abs(R - Color.red(WaterDepth.DEEP)) <= 14 && kotlin.math.abs(G - Color.green(WaterDepth.DEEP)) <= 14 &&
                kotlin.math.abs(B - Color.blue(WaterDepth.DEEP)) <= 14) s[1]++
            if (maxOf(R, G, B) < 32) s[2]++
            s[3]++
        }
        return s
    }
    @Suppress("UNCHECKED_CAST")
    private fun memSource(name: String): Long = (MemWatch::class.java.getDeclaredField("sources").apply { isAccessible = true }.get(MemWatch)
        as Map<String, () -> Long>)[name]?.invoke() ?: -1L

    /** frames until the close tiles' loads are in (MinimapTiles' own reader; the full tiles over the small ones) */
    private fun tilesIn(r: Rig): Bitmap {
        val pending = MinimapTiles::class.java.getDeclaredField("pending").apply { isAccessible = true }.get(MinimapTiles) as Set<*>
        var bm = draw(r.home)
        for (i in 0 until 150) { Thread.sleep(20); settle(2); bm = draw(r.home); if (pending.isEmpty() && i > 10) break }
        return bm
    }

    @Test fun water_depth_from_both_continents() {
        val dir = out?.let { File(it, "ocean-only") }
        fun save(name: String, bm: Bitmap) { val d = dir ?: return; d.mkdirs(); File(d, "$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val report = StringBuilder()
        fun note(l: String) { println("WDM $l"); report.append(l).append('\n') }
        fun mb(b: Long) = "%.2f MB".format(b / 1048576.0)
        for (player in listOf(1411, 1429)) {
            val r = rig(terrain = true, zone = player)
            val pn = if (player == 1411) "kalimdor_player" else "ek_player"
            // the fields first (built once per minimap build, then read from the disk): the shots are the final look
            WaterDepth.fieldNow(0); WaterDepth.fieldNow(1)
            key(r.home, "PADDRIGHT")
            fun spot(zone: Int, u: Float, v: Float): FloatArray? =
                r.room.model.placeOn(zone, 947)?.let { floatArrayOf(it[0] + (it[2] - it[0]) * u, it[1] + (it[3] - it[1]) * v) }
            val spots = listOf(
                Triple("durotar_coast", spot(1411, 0.80f, 0.45f), listOf(1f)),
                Triple("durotar_coast", spot(1411, 0.70f, 0.45f), listOf(3f)),
                Triple("elwynn_lake", spot(1429, 0.73f, 0.49f), listOf(1f)),
                Triple("westfall_coast", spot(1436, 0.20f, 0.50f), listOf(1f)),
                // ocean only (the user, 2026-11-17: "The water in stormwind looks AWFUL"): the city's canals and Loch Modan as the game draws them
                Triple("stormwind", spot(1453, 0.55f, 0.55f), listOf(1f, 3f)),
                Triple("loch_modan", spot(1432, 0.50f, 0.45f), listOf(1f)))
            for ((name, p, zooms) in spots) for (z in zooms) {
                if (p == null) continue
                viewAt(r, z, p)
                frames(r, 8000L)
                val bm = tilesIn(r)
                val w = waterStats(r, bm)
                val dark = 100 * w[2] / w[3].coerceAtLeast(1)
                note("$pn $name z$z: shallow ${100 * w[0] / w[3].coerceAtLeast(1)}%, deep ${100 * w[1] / w[3].coerceAtLeast(1)}%, dark $dark%, " +
                    "terrain ${st(r)[6] == 1f}, other ${r.map.otherContDrawn}, other tiles ${r.map.otherTiles}, sea pictures ${r.map.seaPictures}")
                save("${pn}_${name}_z${z.toInt()}", bm)
                assertTrue("$pn $name z$z: no dark field ($dark%)", dark < 10)
                // (review 3: the gradient past the last tile is the depth field's sea picture, drawn in both ways in - the player's own
                // close tiles and the other continent's terrain - so it never ends on the tiles' edge in the flat fill)
                if (realArt != null && name == "durotar_coast") assertTrue("$pn $name z$z: the sea picture under the tiles", r.map.seaPictures > 0)
                if (realArt != null && name.endsWith("_coast")) {
                    assertTrue("$pn $name z$z: shallows at the coast", w[0] > 0)
                    if (z <= 1f && name == "durotar_coast") assertTrue("$pn $name z$z: deep further out", w[1] > 0)
                }
                // (ocean only, 2026-11-17: Elwynn's lakes keep the game's own colours - no turquoise shallows inland)
                if (realArt != null && name == "loch_modan") {
                    // (the loch round the controls' cursor - the view's right edge is the Wetlands' real coast, rightly with shallows)
                    var sh = 0; var all = 0
                    for (y in 330 until 610 step 4) for (x in 420 until 680 step 4) { val p = bm.getPixel(x, y); all++
                        if (Color.green(p) >= 130 && Color.blue(p) >= 120 && Color.red(p) < Color.green(p) - 30) sh++ }
                    assertTrue("$pn $name: the loch is the art's, no turquoise shallows ($sh of $all)", sh * 100 < all)
                }
                if (realArt != null && name == "elwynn_lake") assertTrue("$pn $name: the lake is the art's, no shallows (${w[0]})", w[0] * 200 < w[3])
            }
            // the open sea between the continents, zoomed out to half the zone
            viewAt(r, 0.5f, seaBetween(r))
            val sea = frames(r, 8000L)
            val ws = waterStats(r, sea)
            note("$pn open sea z0.5: deep ${100 * ws[1] / ws[3].coerceAtLeast(1)}%, dark ${100 * ws[2] / ws[3].coerceAtLeast(1)}%, terrain ${st(r)[6] == 1f}")
            save("${pn}_open_sea_z0.5", sea)
            assertTrue("$pn open sea: the deep colour", ws[1] * 2 > ws[3])
            // the world floor
            for (i in 0 until 12) key(r.home, "PADLSHOULDER")
            val world = frames(r, if (realArt != null) 20_000L else 6_000L)
            assertEquals("down to the floor", st(r)[3], st(r)[2], 0.001f)
            save("${pn}_world_floor", world)
            // nothing per frame: no repaint, no filter on any paint (the markers, route, discs drawn as before)
            val n0 = WaterDepth.repaints
            for (i in 0 until 10) draw(r.home)
            assertEquals("no repaint in a frame", n0, WaterDepth.repaints)
            for (f in listOf("pTerrain", "pArt")) assertNull("$f has no colour filter", (mf(f).get(r.map) as Paint).colorFilter)
            note("$pn memory: WaterDepth ${mb(WaterDepth.bytes())} (fields + sea pictures + scratch), minimap tiles ${mb(memSource("minimap"))}, " +
                "room cache ${mb(r.room.pictureBytes)} (cap 72 MB, unchanged)")
            tearDown()
        }
        dir?.let { it.mkdirs(); File(it, "memory.txt").writeText(report.toString()) }
    }

    // ---------------------------------------------------------------- the world floor's land never vanishes (the user, 2026-11-17: "When
    // zoomed out all of the way on the map the land masses glitch and disappear and all I see is teal in the shape of the land")

    /**
     * [land, all] over the map's field right of the quest list: a pixel that is not water by its colour - not the depth palette's ramp
     * (blue and green well over red), not near-black, not the deep fill - AND lies in art, not in a uniform fill. (Review of 5f9835a1:
     * counted by colour alone, the Painted world's plain parchment square - the room's stand-in while its picture was decoded - scored
     * ~100% land with no land on screen.) The field is cut in blocks of 6 x 6 samples (42 px); a block whose samples all lie within 3 of
     * each other in R, G and B is a fill (parchment, black, a flat colour) and none of its pixels count as land; the parchment's own
     * colour never counts either.
     */
    private fun landStats(r: Rig, bm: Bitmap): IntArray {
        val loc = IntArray(2); r.map.getLocationInWindow(loc)
        val xs = (loc[0] + r.map.width / 3 until loc[0] + r.map.width - 10 step 7).filter { it in 0 until bm.width }
        val ys = (loc[1] + 10 until loc[1] + r.map.height - 10 step 7).filter { it in 0 until bm.height }
        val px = Array(ys.size) { j -> IntArray(xs.size) { i -> bm.getPixel(xs[i], ys[j]) } }
        val s = IntArray(2)
        val B = 6
        for (bj in ys.indices step B) for (bi in xs.indices step B) {
            val lo = intArrayOf(255, 255, 255); val hi = intArrayOf(0, 0, 0)
            for (j in bj until minOf(bj + B, ys.size)) for (i in bi until minOf(bi + B, xs.size)) {
                val p = px[j][i]
                val ch = intArrayOf(Color.red(p), Color.green(p), Color.blue(p))
                for (k in 0..2) { lo[k] = minOf(lo[k], ch[k]); hi[k] = maxOf(hi[k], ch[k]) }
            }
            val fill = (0..2).all { hi[it] - lo[it] <= 3 }
            for (j in bj until minOf(bj + B, ys.size)) for (i in bi until minOf(bi + B, xs.size)) {
                val p = px[j][i]
                val R = Color.red(p); val G = Color.green(p); val Bl = Color.blue(p)
                val water = (G > R + 22 && Bl > R + 22) || maxOf(R, G, Bl) < 32
                if (!water && !fill && p != PARCHMENT) s[0]++
                s[1]++
            }
        }
        return s
    }
    /** the least land a frame may show (real art at the world floor: Terrain ~17-21% - its sea is the depth ramp -, Painted ~57-72% - the
     *  painting's sea is parchment; the review's failures: the parchment square 0%, Eastern Kingdoms' first frame out black with a patch of
     *  close tiles ~30%); the made-up art is solid colours, so there it only has to be some */
    private fun landFloor(terrain: Boolean) = if (realArt == null) 5 else if (terrain) 12 else 40
    /** the room's parchment fill (WorldMapRoomView.drawPicture, a canvas whose picture is not decoded yet) */
    private val PARCHMENT = Color.rgb(196, 168, 116)

    @Test fun land_stats_reject_a_uniform_fill() {
        val r = rig(terrain = true)
        fun shot(fill: (Canvas) -> Unit): Bitmap {
            val bm = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888); fill(Canvas(bm)); return bm
        }
        // the parchment square, a flat land colour, black: no land
        for (col in listOf(PARCHMENT, Color.rgb(150, 120, 70), Color.BLACK)) {
            val s = landStats(r, shot { it.drawColor(col) })
            assertEquals("a uniform fill ${Integer.toHexString(col)} is no land", 0, s[0])
        }
        // textured land (a fine checker of two land colours): land
        val s = landStats(r, shot { c ->
            c.drawColor(Color.rgb(150, 120, 70))
            val p = Paint().apply { color = Color.rgb(120, 140, 60) }
            for (y in 0 until 1080 step 10) for (x in (if (y / 10 % 2 == 0) 0 else 10) until W step 20) c.drawRect(x.toFloat(), y.toFloat(), x + 10f, y + 10f, p)
        })
        assertTrue("textured land counts (${s[0]} of ${s[1]})", s[0] * 10 > s[1] * 9)
        tearDown()
    }

    /** every frame of a short zoom sequence: out to the world floor step by step (no wait for the pictures), a pan there, back in to the
     *  zone and out to the floor again; each frame must show land (a picture still being made or let go on the zoom-in shows the coarser
     *  one, the finer one, or the depth field's picture of the continent - its land, never the shallows' teal in the land's shape) */
    @Test fun the_world_floor_always_shows_the_land() {
        val strip = out?.let { File(it, "ocean-only") }
        val failures = ArrayList<String>()
        for (terrain in listOf(true, false)) for (player in listOf(1411, 1429)) {
            val r = rig(terrain = terrain, zone = player)
            val pn = (if (player == 1411) "kalimdor_player" else "ek_player") + if (terrain) "_terrain" else "_painted"
            // (the fields are on the disk by then on the phone: read now, as the app does at its first zoom-out)
            WaterDepth.fieldNow(0); WaterDepth.fieldNow(1)
            key(r.home, "PADDRIGHT")
            val shots = ArrayList<Bitmap>()
            val worst = StringBuilder()
            val bad = ArrayList<String>()
            var minLand = 100
            val lands = StringBuilder()
            fun frame(label: String) {
                settle(1)
                val bm = draw(r.home)
                val s = landStats(r, bm)
                val land = 100 * s[0] / s[1].coerceAtLeast(1)
                if (land < minLand) { minLand = land; worst.setLength(0); worst.append("$label zoom ${st(r)[2]} land $land%") }
                if (strip != null) shots.add(Bitmap.createScaledBitmap(bm, W / 4, 1080 / 4, true))
                lands.append(" $land")
                if (land < landFloor(terrain)) bad.add("$label (zoom ${st(r)[2]}) $land%")
            }
            for (i in 0 until 12) { key(r.home, "PADLSHOULDER"); for (k in 0 until 3) frame("out $i.$k") }
            assertEquals("$pn: down to the floor", st(r)[3], st(r)[2], 0.001f)
            for (k in 0 until 4) { Thread.sleep(40); frame("floor $k") }
            // a pan at the floor
            val px = mf("panX").getFloat(r.map)
            for (k in 0 until 4) { mf("panX").setFloat(r.map, px + 0.4f * (k + 1)); r.map.invalidate(); frame("pan $k") }
            // (and back: zooming in over the open sea would be a sea, rightly)
            mf("panX").setFloat(r.map, px); r.map.invalidate(); frame("pan back")
            // back in to the zone (its pictures let go there), then out again
            for (i in 0 until 12) { key(r.home, "PADRSHOULDER"); frame("in $i") }
            for (k in 0 until 3) { Thread.sleep(40); frame("zone $k") }
            for (i in 0 until 12) { key(r.home, "PADLSHOULDER"); for (k in 0 until 2) frame("out again $i.$k") }
            println("WFL $pn: ${shots.size} frames, least land: $worst")
            println("WFL $pn per frame:$lands")
            if (strip != null && shots.isNotEmpty()) {
                val cols = 8; val tw = W / 4; val th = 1080 / 4
                val sheet = Bitmap.createBitmap(cols * tw, ((shots.size + cols - 1) / cols) * th, Bitmap.Config.ARGB_8888)
                val c = Canvas(sheet)
                shots.forEachIndexed { i, b -> c.drawBitmap(b, ((i % cols) * tw).toFloat(), ((i / cols) * th).toFloat(), null) }
                strip.mkdirs()
                File(strip, "zoom_sequence_$pn.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            if (bad.isNotEmpty()) failures.add("$pn: ${bad.size} frames: $bad")
            tearDown()
        }
        assertTrue("land on screen in every frame (under ${landFloor(true)}% Terrain / ${landFloor(false)}% Painted of the field: $failures)", failures.isEmpty())
    }
}
