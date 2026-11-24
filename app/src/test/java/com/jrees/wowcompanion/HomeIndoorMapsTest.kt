package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import java.io.File
import java.util.zip.Deflater

/**
 * IRONFORGE ON BOTH MAPS (the user, 2026-11-23, on test 1523: "I cant see iron forges map at all when walking around in it. I feel like
 * our inside maps need work"). The real MapView, as Home's map column (compact) and as the open map, over a made-up Ironforge: one built
 * shop and one built room with floor only at its far end (our builder's plans), the game's own indoor art for one hall group
 * (wmominimap.json, a tile), Ironforge's painted city map, and the player indoors at:
 *  - a hall point inside the shop's WORLD box but outside its turned walls (fix 3: the shop is not picked);
 *  - a point in that room's own rect with no floor of it near (fix 3: passed over once its height field is read);
 *  - the hall (A: the game's tiles by position, B: the painted city map under them, no note);
 *  - inside the city but in no hall group (B: the painted map, and fix 4: "No indoor map here yet").
 * Everything new is read by reflection so the same file runs (and fails) on the code before the fix (0f57b547).
 *
 * With IA_ART_ROOT (an art folder: interiors/, wmominimap.json, wmomm/, worldmap/, minimap/, ui/, fonts/) and IA_SHOTS_DIR, [shots]
 * draws Ironforge's ring, the Great Forge, an entrance hall and a shop on both maps from the PC's own data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeIndoorMapsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val rootField = ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }
    private var was: Any? = null
    private lateinit var root: File
    private lateinit var game: File

    // Ironforge's root placement (the PC host bake) and its painted map's rect
    private val ox = -5008.526692708332; private val oy = -849.4290364583321
    private val cs = 0.6360782202777643; private val sn = -0.7716245833877197
    private val IF_RECT = doubleArrayOf(-4569.2412, -5096.8457, -713.5914, -1504.2164)      // top, bottom, left, right
    private fun wx(lx: Double, ly: Double) = ox - lx * cs + ly * sn
    private fun wy(lx: Double, ly: Double) = oy - lx * sn - ly * cs
    private val BUILD = "00000000000000000000000000000000"
    private val GEN = "g0123456789abcdef0123456789abcdef"
    private val BDIR = "b00000000000000000000000000000001"
    private val SHOP = "7801267_d0_r9"
    private val ROOM = "7801267_d0_r10"
    private val SHOP_RECT = doubleArrayOf(-320.0, -300.0, -10.0, 10.0)
    private val ROOM_RECT = doubleArrayOf(-280.0, -260.0, 40.0, 60.0)
    private val HALL = floatArrayOf(-400f, -100f, -5f, -250f, 100f, 80f)
    private val TILE_FID = 1000001

    @Before fun setUp() {
        was = rootField.get(null)
        val real = System.getenv("IA_ART_ROOT")
        root = if (real != null) File("build/tmp/ia-art").absoluteFile.also { r -> if (!File(r, "interiors").isDirectory) File(real).copyRecursively(r) }
            else java.nio.file.Files.createTempDirectory("ia-art").toFile().also { madeUp(it) }
        rootField.set(null, root)
        game = java.nio.file.Files.createTempDirectory("ia-game").toFile()
        File(game, ".build.info").writeText("Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|Version!STRING:0|Product!STRING:0\nus|1|$BUILD|1.0|wowf\n")
        app.getSharedPreferences(GameFiles.PREFS, android.content.Context.MODE_PRIVATE).edit().putString(GameFiles.KEY_GAME_PATH, game.path).commit()
        if (!File(root, MinimapTiles.INDEX).isFile) File(root, MinimapTiles.INDEX).apply { parentFile?.mkdirs() }.writeText("""{"continents":{}}""")
        MinimapTiles.refresh()
        Settings.init(app)
    }

    @After fun tearDown() {
        rootField.set(null, was)
        MinimapTiles.refresh()
        game.deleteRecursively()
        if (System.getenv("IA_ART_ROOT") == null) root.deleteRecursively()
    }

    // ---------------------------------------------------------------- the made-up Ironforge

    private fun png(f: File, w: Int, h: Int, color: Int) {
        f.parentFile?.mkdirs()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun grid(nx: Int, ny: Int, floorAt: (Int, Int) -> Boolean): ByteArray {
        val raw = ByteArray(nx * ny * 4)
        for (j in 0 until ny) for (i in 0 until nx) if (floorAt(i, j)) raw[(j * nx + i) * 4] = 2
        val d = Deflater(); d.setInput(raw); d.finish()
        val buf = ByteArray(raw.size + 64); val n = d.deflate(buf); d.end()
        return buf.copyOf(n)
    }

    private fun place(key: String, r: DoubleArray, gridFile: String, nx: Int, ny: Int): Pair<JSONObject, JSONObject> {
        val level = JSONObject().put("z0", -1).put("z1", 6).put("zlo", 0).put("zhi", 1).put("img", "$BDIR--$key.png").put("outline", JSONObject.NULL)
            .put("lxmin", r[0]).put("lxmax", r[1]).put("lymin", r[2]).put("lymax", r[3]).put("w", 40).put("h", 40)
        val model = JSONObject().put("levels", JSONArray().put(level)).put("grid", JSONObject().put("file", "$BDIR--$gridFile").put("cell", 1.0)
            .put("nx", nx).put("ny", ny).put("lx0", r[0]).put("ly0", r[2]).put("zmin", 0).put("zscale", 1.0).put("slots", 4)).put("stairs", JSONArray())
        val xs = listOf(r[0], r[1]).flatMap { lx -> listOf(r[2], r[3]).map { ly -> wx(lx, ly) } }
        val ys = listOf(r[0], r[1]).flatMap { lx -> listOf(r[2], r[3]).map { ly -> wy(lx, ly) } }
        val pl = JSONObject().put("m", key).put("l2w", JSONArray(listOf(ox, oy, cs, sn)))
            .put("wxmin", xs.min()).put("wxmax", xs.max()).put("wymin", ys.min()).put("wymax", ys.max())
        return model to pl
    }

    private fun madeUp(r: File) {
        val snap = File(r, "interiors/snapshots/$GEN").apply { mkdirs() }
        val b = File(r, "interiors/buildings/$BDIR").apply { mkdirs() }
        png(File(b, "$SHOP.png"), 40, 40, Color.rgb(200, 170, 90)); png(File(b, "$ROOM.png"), 40, 40, Color.rgb(90, 170, 200))
        File(b, "shop.grid").writeBytes(grid(20, 20) { _, _ -> true })
        File(b, "room.grid").writeBytes(grid(20, 20) { i, _ -> i < 5 })            // floor only at its far end (lx < -275)
        val (sm, sp) = place(SHOP, SHOP_RECT, "shop.grid", 20, 20)
        val (rm, rp) = place(ROOM, ROOM_RECT, "room.grid", 20, 20)
        File(snap, "models_0.json").writeText(JSONObject().put(SHOP, sm).put(ROOM, rm).toString())
        File(snap, "places_0.json").writeText(JSONObject().put("0", JSONArray().put(sp).put(rp)).toString())
        File(r, "interiors/current.json").writeText(JSONObject().put("schema", 2).put("generation", GEN).put("build", BUILD)
            .put("continents", JSONArray().put(0)).toString())
        // the game's own art: one hall group with one tile (its texture 256 x 256)
        png(File(r, WmoMinimap.tileFile(TILE_FID)), 256, 256, Color.rgb(170, 60, 60))
        val g = JSONObject().put("t", JSONArray().put(JSONArray(listOf(0, 0, TILE_FID, 256, 256)))).put("bb", JSONArray(HALL.map { it.toDouble() }))
        File(r, "wmominimap.json").writeText(JSONObject().put("v", 2).put("roots", JSONObject()
            .put("7801267", JSONObject().put("wmo", 20884).put("groups", JSONObject().put("65", g)))).toString())
        // Ironforge's painted map (the Map room's art)
        File(r, "worldmap/1455").mkdirs()
        File(r, "worldmap/1455/index.json").writeText(JSONObject().put("uiMapID", 1455).put("name", "Ironforge").put("w", 1002).put("h", 668)
            .put("rect", JSONArray(listOf(0.0) + IF_RECT.toList())).put("overlays", JSONArray()).toString())
        png(File(r, "worldmap/1455/base.webp"), 1002, 668, Color.rgb(60, 120, 60))
    }

    // ---------------------------------------------------------------- the rig

    private fun doc(wx: Double, wy: Double, indoors: Boolean = true): JSONObject {
        val (t, bt, l, rr) = IF_RECT.toList()
        return JSONObject().put("type", "map").put("ok", true).put("mapID", 1455).put("zone", "Ironforge").put("subzone", "")
            .put("x", (l - wy) / (l - rr)).put("y", (t - wx) / (t - bt)).put("cont", 0).put("inst", false).put("indoors", indoors)
            .put("wx", wx).put("wy", wy).put("facing", 0.5)
            .put("rect", JSONObject().put("c", 0).put("t", t).put("b", bt).put("l", l).put("r", rr))
    }

    /** the map in an activity's window (its posts - the rooms, the art index - land only once it is attached) */
    private fun map(compact: Boolean, w: Int, h: Int, d: JSONObject): MapView {
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val v = MapView(act)
        act.setContentView(android.widget.FrameLayout(act).apply { addView(v, android.widget.FrameLayout.LayoutParams(w, h)) })
        ShadowLooper.idleMainLooper()
        v.compact = compact
        v.update(d, 0)
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        return v
    }

    /** what a frame drew: its texts, the game tiles (a 256 px picture through a matrix), the painted city map (1002 x 668) */
    private class Rec(val b: Bitmap) : Canvas(b) {
        val texts = ArrayList<String>()
        var tiles = 0
        var painted = 0
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) { texts += text; super.drawText(text, x, y, paint) }
        override fun drawBitmap(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {
            if (bitmap.width == bitmap.height && (bitmap.width == 256 || bitmap.width == 128)) tiles++
            super.drawBitmap(bitmap, matrix, paint)
        }
        override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) {
            if (bitmap.width == 1002 && bitmap.height == 668) painted++
            super.drawBitmap(bitmap, src, dst, paint)
        }
    }

    private fun frame(v: MapView, w: Int, h: Int): Rec = Rec(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)).also { it.drawColor(Color.BLACK); v.draw(it) }

    /** frames until [done] (the rooms, the art index and the pictures read off the UI thread) */
    private fun settle(v: MapView, w: Int, h: Int, ms: Long = 6_000L, done: (Rec) -> Boolean): Rec {
        val until = System.currentTimeMillis() + ms
        var r = frame(v, w, h)
        while (!done(r) && System.currentTimeMillis() < until) { Thread.sleep(20); ShadowLooper.idleMainLooper(); r = frame(v, w, h) }
        return r
    }

    private val placeHere = MapView::class.java.getDeclaredMethod("placeHere", JSONObject::class.java).apply { isAccessible = true }
    private fun pickedKey(v: MapView, d: JSONObject): String? = placeHere.invoke(v, d)?.let { p ->
        p.javaClass.getDeclaredField("fid").apply { isAccessible = true }.get(p) as String
    }
    /** the rooms of continent 0 are read (MapView's own thread) */
    private fun roomsRead(v: MapView): Boolean = MapView::class.java.getDeclaredField("placesFor").apply { isAccessible = true }.getInt(v) == 0

    private fun waitRooms(v: MapView, d: JSONObject) {
        val until = System.currentTimeMillis() + 6_000L
        while (System.currentTimeMillis() < until) { pickedKey(v, d); frame(v, 520, 420); if (roomsRead(v)) break; Thread.sleep(20); ShadowLooper.idleMainLooper() }
        assertTrue("the rooms were read", roomsRead(v))
    }

    // ---------------------------------------------------------------- fix 3

    @Test fun aShopsWorldBoxNoLongerClaimsTheHallBesideIt() {
        // the shop's middle: the shop
        val inShop = doc(wx(-310.0, 0.0), wy(-310.0, 0.0))
        val v = map(true, 520, 420, inShop)
        waitRooms(v, inShop)
        assertEquals(SHOP, pickedKey(v, inShop))
        // a hall point in the corner of the shop's world box (the city is turned about 50 degrees), outside its walls
        val xs = listOf(SHOP_RECT[0], SHOP_RECT[1]).flatMap { lx -> listOf(SHOP_RECT[2], SHOP_RECT[3]).map { ly -> wx(lx, ly) } }
        val ys = listOf(SHOP_RECT[0], SHOP_RECT[1]).flatMap { lx -> listOf(SHOP_RECT[2], SHOP_RECT[3]).map { ly -> wy(lx, ly) } }
        val corner = doc(xs.min() + 1.0, ys.min() + 1.0)
        v.update(corner, 0)
        for (i in 0 until 20) { pickedKey(v, corner); Thread.sleep(10); ShadowLooper.idleMainLooper() }
        assertNull("the hall beside the shop is not the shop", pickedKey(v, corner))
    }

    @Test fun aRoomWithNoFloorNearThePlayerIsPassedOver() {
        // inside the room's own rect, 10 yd from its only floor: once its height field is read, not the room
        val d = doc(wx(-265.0, 50.0), wy(-265.0, 50.0))
        val v = map(true, 520, 420, d)
        waitRooms(v, d)
        val until = System.currentTimeMillis() + 4_000L
        var k = pickedKey(v, d)
        while (k != null && System.currentTimeMillis() < until) { Thread.sleep(20); ShadowLooper.idleMainLooper(); k = pickedKey(v, d) }
        assertNull("no floor of it here: no plan", k)
        // at its floor it is the room
        val at = doc(wx(-278.0, 50.0), wy(-278.0, 50.0))
        v.update(at, 0)
        assertEquals(ROOM, pickedKey(v, at))
    }

    // ---------------------------------------------------------------- A, B and fix 4 on both maps

    private fun hallCase(compact: Boolean, w: Int, h: Int) {
        val d = doc(wx(-350.0, -50.0), wy(-350.0, -50.0))
        val v = map(compact, w, h, d)
        waitRooms(v, d)
        val r = settle(v, w, h) { it.tiles > 0 && it.painted > 0 }
        assertTrue("the game's art for the hall by position (${r.tiles} tiles)", r.tiles > 0)
        assertTrue("the painted city map under it", r.painted > 0)
        assertFalse("no note: there is a map here", r.texts.contains("No indoor map here yet"))
    }

    @Test fun theGamesArtShowsInAHallWithNoBuiltRoomHome() = hallCase(true, 520, 420)
    @Test fun theGamesArtShowsInAHallWithNoBuiltRoomOpenMap() = hallCase(false, 1240, 1000)

    private fun noMapCase(compact: Boolean, w: Int, h: Int) {
        val d = doc(wx(-150.0, 150.0), wy(-150.0, 150.0))
        val v = map(compact, w, h, d)
        waitRooms(v, d)
        val r = settle(v, w, h) { it.painted > 0 && it.texts.contains("No indoor map here yet") }
        assertEquals(0, r.tiles)
        assertTrue("the painted city map, not the mountain zoomed in", r.painted > 0)
        assertTrue("the note: ${r.texts}", r.texts.contains("No indoor map here yet"))
    }

    @Test fun indoorsWithNoMapSaysSoHome() = noMapCase(true, 520, 420)
    @Test fun indoorsWithNoMapSaysSoOpenMap() = noMapCase(false, 1240, 1000)

    @Test fun outdoorsNothingChanges() {
        val d = doc(wx(-150.0, 150.0), wy(-150.0, 150.0), indoors = false)
        val v = map(true, 520, 420, d)
        waitRooms(v, d)
        val r = settle(v, 520, 420, ms = 800L) { false }
        assertEquals(0, r.tiles); assertEquals("outdoors: the outdoor map as before", 0, r.painted)
        assertFalse(r.texts.contains("No indoor map here yet"))
    }

    // ---------------------------------------------------------------- the shots (the PC's own data)

    @Test fun shots() {
        val out = System.getenv("IA_SHOTS_DIR")?.let { File(it) } ?: return
        if (System.getenv("IA_ART_ROOT") == null) return
        out.mkdirs()
        val spots = listOf("ring" to doubleArrayOf(-4840.0, -1140.0), "great_forge" to doubleArrayOf(-4910.0, -940.0),
            "entrance_hall" to doubleArrayOf(-4799.0, -889.0), "shop" to doubleArrayOf(-5028.0, -1127.0))
        val log = StringBuilder()
        for ((name, p) in spots) for ((mode, size) in listOf("home" to intArrayOf(520, 420), "openmap" to intArrayOf(1240, 1000))) {
            val d = doc(p[0], p[1])
            val v = map(mode == "home", size[0], size[1], d)
            waitRooms(v, d)
            // a few position ticks for the storey tracker, then the pictures
            val trackFromMap = MapView::class.java.getDeclaredMethod("trackFromMap", JSONObject::class.java).apply { isAccessible = true }
            for (i in 0 until 30) { trackFromMap.invoke(v, d); Thread.sleep(20); ShadowLooper.idleMainLooper(); frame(v, size[0], size[1]) }
            var last = -1; var still = 0
            var r = frame(v, size[0], size[1])
            val until = System.currentTimeMillis() + 8_000L
            while (System.currentTimeMillis() < until) {
                Thread.sleep(40); ShadowLooper.idleMainLooper(); r = frame(v, size[0], size[1])
                val k = r.tiles * 1000 + r.painted
                if (k == last) { if (++still >= 8) break } else still = 0
                last = k
            }
            File(out, "${name}_$mode.png").outputStream().use { r.b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            log.append("$name $mode: room ${pickedKey(v, d)}, game tiles ${r.tiles}, painted ${r.painted}, texts ${r.texts.filter { it.length > 8 }}\n")
        }
        // fix 4's note on a building with no indoor map (the Thunderbrew Distillery in Kharanos: none in this capitals-only snapshot)
        val dm = JSONObject(File(root, "worldmap/1426/index.json").readText()).getJSONArray("rect")
        val (t, bt, l, rr) = (1..4).map { dm.getDouble(it) }
        val kx = -5601.0; val ky = -531.0
        val nd = JSONObject().put("type", "map").put("ok", true).put("mapID", 1426).put("zone", "Dun Morogh").put("subzone", "Kharanos")
            .put("x", (l - ky) / (l - rr)).put("y", (t - kx) / (t - bt)).put("cont", 0).put("inst", false).put("indoors", true)
            .put("wx", kx).put("wy", ky).put("facing", 0.5).put("rect", JSONObject().put("c", 0).put("t", t).put("b", bt).put("l", l).put("r", rr))
        for ((mode, size) in listOf("home" to intArrayOf(520, 420), "openmap" to intArrayOf(1240, 1000))) {
            val v = map(mode == "home", size[0], size[1], nd)
            waitRooms(v, nd)
            var r = frame(v, size[0], size[1])
            val until = System.currentTimeMillis() + 6_000L
            while (System.currentTimeMillis() < until) { Thread.sleep(40); ShadowLooper.idleMainLooper(); r = frame(v, size[0], size[1]) }
            File(out, "note_kharanos_$mode.png").outputStream().use { r.b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            log.append("note_kharanos $mode: room ${pickedKey(v, nd)}, texts ${r.texts.filter { it.length > 8 }}\n")
        }
        File(out, "shots.txt").writeText(log.toString())
    }
}
