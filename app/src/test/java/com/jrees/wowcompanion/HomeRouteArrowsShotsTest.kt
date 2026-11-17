package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
import kotlin.math.abs
import kotlin.math.hypot

/**
 * feat/route-arrows, HOME'S MAP ROUTE OFF THE DEVICE (the user, 2026-11-17: "on the mini map every 5 dots I want a blue arrow so that
 * its consistent"): the real MapView's route drawing ([MapView.drawRouteDots], used by Polestar's path, SafePath's stretches, the
 * corpse run, the cursor's line and QuestTrail's path): then "I want it to be 1 arrow every 5 dots": five gold dots, then the
 * compass's light blue chevron in a 6th slot of its own, turned along the route; then the real HomeView with Polestar's route on
 * the small map and the open module, blue arrows on it 96 px apart.
 * The checks run always; the pictures only when RA_SHOTS_DIR is set (RA_ART_ROOT = an art root laid out like the app's).
 *   RA_SHOTS_DIR=<dir> RA_ART_ROOT=<root> gradlew :app:testModernDebugUnitTest --tests "*HomeRouteArrowsShotsTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeRouteArrowsShotsTest {
    private val out: File? = System.getenv("RA_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT
    private val ELWYNN = doubleArrayOf(-7939.583, -10254.166, 1535.4166, -1935.4166)      // t, b, l, r (continent 0)

    private fun blue(p: Int) = abs(Color.red(p) - 120) < 40 && abs(Color.green(p) - 200) < 40 && Color.blue(p) > 215
    private fun gold(p: Int) = Color.red(p) > 200 && Color.green(p) in 170..230 && Color.blue(p) < 90

    private fun routeDots(v: MapView, c: Canvas, p: Path, a: Float, t: Long) {
        MapView::class.java.getDeclaredMethod("drawRouteDots", Canvas::class.java, Path::class.java, Float::class.javaPrimitiveType,
            Long::class.javaPrimitiveType).apply { isAccessible = true }.invoke(v, c, p, a, t)
    }

    @Test fun aBlueArrowAfterEveryFiveDotsAlongTheRoute() {
        val v = MapView(app)
        val b = Bitmap.createBitmap(420, 120, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Color.rgb(40, 60, 34))
        val p = Path().apply { moveTo(20f, 60f); lineTo(400f, 60f) }
        routeDots(v, c, p, 1f, 0L)
        // t = 0: dots at 0, 16, 32, 48, 64 (+ 96 k), arrows at 80 (+ 96 k) from the start
        val arrows = RouteArrows.arrowDistances(380f, 0L)
        assertEquals(listOf(80f, 176f, 272f, 368f), arrows.toList())
        assertEquals(listOf(0f, 16f, 32f, 48f, 64f), RouteArrows.dotDistances(380f, 0L).take(5))
        for (s in arrows) {
            val x = (20f + s).toInt()
            assertTrue("a blue arrow at $s (apex ahead)", blue(b.getPixel(x + 3, 60)))
            assertTrue("its arms behind and to the sides", blue(b.getPixel(x - 2, 60 - 5)) && blue(b.getPixel(x - 2, 60 + 5)))
            assertTrue("pointing toward the goal (+x): nothing of it ahead of the apex", !blue(b.getPixel(x + 9, 60)))
            assertTrue("no gold dot under it", !gold(b.getPixel(x, 60)))
        }
        for (s in RouteArrows.dotDistances(380f, 0L)) assertTrue("a gold dot at $s", gold(b.getPixel((20f + s).toInt(), 60)))
        // the arrows turn with the route: a path going down (+y): the apex below
        val b2 = Bitmap.createBitmap(120, 420, Bitmap.Config.ARGB_8888)
        val c2 = Canvas(b2); c2.drawColor(Color.rgb(40, 60, 34))
        routeDots(v, c2, Path().apply { moveTo(60f, 20f); lineTo(60f, 400f) }, 1f, 0L)
        assertTrue("turned down the route", blue(b2.getPixel(60, 20 + 80 + 3)) && !blue(b2.getPixel(60, 20 + 80 + 9)))
        // dimmed with the stretch beyond the next checkpoint
        val b3 = Bitmap.createBitmap(420, 120, Bitmap.Config.ARGB_8888)
        val c3 = Canvas(b3); c3.drawColor(Color.BLACK)
        routeDots(v, c3, p, 0.4f, 0L)
        assertTrue("dimmed", Color.blue(b3.getPixel(20 + 80 + 3, 60)) < 140)
        out?.let { d ->
            d.mkdirs()
            val z = Bitmap.createScaledBitmap(b, 420 * 3, 120 * 3, false)
            File(d, "route_dots_strip_x3.png").outputStream().use { z.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    // ---------------------------------------------------------------- Home's map with Polestar's route
    private fun art() {
        val root = File("build/tmp/ra-art").absoluteFile
        System.getenv("RA_ART_ROOT")?.let { src ->
            for (d in listOf("ui", "fonts", "minimap", "worldmap", "seamless"))
                if (!File(root, d).isDirectory) File(src, d).takeIf { it.isDirectory }?.copyRecursively(File(root, d))
            if (!File(root, "icons.pak").isFile) File(src, "icons.pak").takeIf { it.isFile }?.copyTo(File(root, "icons.pak"))
        }
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, root)
    }
    private fun lay(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, W, H)
    }
    private fun settle(n: Int = 30) { for (i in 0 until n) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() } }
    private fun mapDoc() = JSONObject().put("ok", true).put("mapID", 1429).put("zone", "Elwynn Forest").put("subzone", "Goldshire")
        .put("x", 0.42).put("y", 0.62).put("facing", 2.4)
        .put("rect", JSONObject().put("c", 0).put("t", ELWYNN[0]).put("b", ELWYNN[1]).put("l", ELWYNN[2]).put("r", ELWYNN[3]))
    /** world (x, y) of a 0..1 place on Elwynn (PoiStore.Rect's swap: nx from world y, ny from world x) */
    private fun world(nx: Double, ny: Double) = doubleArrayOf(ELWYNN[0] - ny * (ELWYNN[0] - ELWYNN[1]), ELWYNN[2] - nx * (ELWYNN[2] - ELWYNN[3]))

    @org.junit.After fun forget() { Settings.polestarGuide = null }

    private fun draw(home: HomeView): Bitmap {
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        for (pass in 0..2) {
            val c = Canvas(bm); c.drawColor(Theme.ink); home.draw(c)
            ShadowLooper.idleMainLooper(); Thread.sleep(60); ShadowLooper.idleMainLooper()
        }
        return bm
    }
    /** the blue pixels [with] has and [without] has not, gathered into blobs: their centres */
    private fun blueBlobs(with: Bitmap, without: Bitmap): List<FloatArray> {
        val seen = BooleanArray(W * H)
        val out = ArrayList<FloatArray>()
        for (y in 0 until H) for (x in 0 until W) {
            val i = y * W + x
            if (seen[i] || !blue(with.getPixel(x, y)) || blue(without.getPixel(x, y))) continue
            var sx = 0f; var sy = 0f; var n = 0
            val st = ArrayDeque<Int>(); st.add(i); seen[i] = true
            while (st.isNotEmpty()) {
                val j = st.removeLast(); val jx = j % W; val jy = j / W
                sx += jx; sy += jy; n++
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val kx = jx + dx; val ky = jy + dy
                    if (kx !in 0 until W || ky !in 0 until H) continue
                    val k = ky * W + kx
                    if (!seen[k] && blue(with.getPixel(kx, ky)) && !blue(without.getPixel(kx, ky))) { seen[k] = true; st.add(k) }
                }
            }
            if (n >= 8) out.add(floatArrayOf(sx / n, sy / n, n.toFloat()))
        }
        return out
    }

    @Test fun homesMapPolestarRouteWearsTheArrows() {
        art()
        HomeSettings.init(app); Settings.init(app)
        Settings.polestarGuide = PolestarGuide.ON
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val top = FrameLayout(act)
        act.setContentView(top)
        val map = MapView(act); val cast = CastView(act) { }; val chat = ChatView(act) { }
        val holder = FrameLayout(act); holder.addView(map); holder.addView(cast); holder.addView(chat); top.addView(holder)
        val room = WorldMapRoomView(act).apply { visibility = View.GONE }
        top.addView(room)
        room.loadArt()
        for (i in 0 until 200) { Thread.sleep(10); ShadowLooper.idleMainLooper(); if (room.backdropRoot(1429) != null) break }
        map.worldArt = room.homeArt
        map.worldFull = { true }
        val home = HomeView(act, map, cast, chat) { }
        top.addView(home, FrameLayout.LayoutParams(W, H))
        home.borrow(); lay(home)
        map.update(mapDoc(), 0); room.update(mapDoc())
        home.tick(); settle(); lay(home)
        val noRoute = draw(home)
        // Polestar guiding (the row holds), its walk: from the player north-east, then east
        map.polestar.tick(null, android.os.SystemClock.elapsedRealtime(), ps = true)
        val p0 = world(0.42, 0.62); val p1 = world(0.55, 0.48); val p2 = world(0.72, 0.47)
        map.polestar.onRoute(JSONObject().put("type", "pnavroute").put("legs", JSONArray().put(JSONObject().put("k", "walk").put("c", 0)
            .put("p", JSONArray(listOf(p0[0], p0[1], p1[0], p1[1], p2[0], p2[1]))))), android.os.SystemClock.elapsedRealtime())
        settle(4)
        val small = draw(home)
        val blobs = blueBlobs(small, noRoute)
        save(small, "home_small_map_polestar_arrows")
        assertTrue("blue arrows on the small map's Polestar route (${blobs.size})", blobs.size >= 2)
        val near = blobs.sortedBy { it[0] }
        val gaps = near.zipWithNext { a, b -> hypot(b[0] - a[0], b[1] - a[1]) }
        assertTrue("96 px apart along the route ($gaps)", gaps.count { abs(it - 96f) < 6f } >= 1)
        // the open module: the same route, the same arrows
        assertTrue(home.openMapModule()); settle(); lay(home)
        map.polestar.onRoute(JSONObject().put("type", "pnavroute").put("legs", JSONArray().put(JSONObject().put("k", "walk").put("c", 0)
            .put("p", JSONArray(listOf(p0[0], p0[1], p1[0], p1[1], p2[0], p2[1]))))), android.os.SystemClock.elapsedRealtime())
        val open = draw(home)
        save(open, "home_open_map_polestar_arrows")
        // and a moment later: they have moved along with the dots
        settle(30)
        save(draw(home), "home_open_map_polestar_arrows_later")
    }

    private fun save(b: Bitmap, name: String) {
        val d = out ?: return
        d.mkdirs()
        File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
