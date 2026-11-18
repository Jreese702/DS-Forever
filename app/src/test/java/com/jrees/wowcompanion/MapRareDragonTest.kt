package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.nio.file.Files

/**
 * THE MAPS' RARES WEAR THE SILVER DRAGON (the user's pick "Silver dragon", 2026-11-17, after "the Skull above mobs is confusing, I think
 * we need to scrap that, we do however need to add the dragon Icon for an Elite and a silver one for rare spawns"): the rare spawn layer
 * (QuestieDB's rares) drew the minimap's vignette marks (a star; a rare elite the star with a dragon); a rare and a rare elite now draw
 * the game's silver plate dragon, kit `elite_silver`, else UiKit.eliteDragon's drawn stand-in. One drawing (UiKit.spawnSpot) serves
 * Home's map (MapView) and the Map room (WorldMapRoomView).
 *
 * With MAPRARE_SHOTS_DIR set it renders Home's map and the Map room with a rare and a rare elite beside the player, over the art root
 * MAPRARE_ART_ROOT (ui/kit, minimap, worldmap).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MapRareDragonTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun art(root: File?) {
        val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, root)
        UiKit.root = UiKit.root
        // (the full suite, 2026-11-18: another class's made-up minimap - Eastern Kingdoms' tiles solid red - stayed loaded and filled
        // Home's map red under the rares; the tiles and terrain are read again from this root)
        MinimapTiles.refresh(); TerrainOverview.refresh()
    }

    @After fun noArt() = art(null)

    /** a flat kit: the old marks pure red, the silver dragon pure cyan, the gold one pure magenta */
    private fun flatKit(): File {
        val root = Files.createTempDirectory("rare-kit").toFile()
        val kit = File(root, "ui/kit").apply { mkdirs() }
        for ((n, col) in listOf("mm_rare" to Color.RED, "mm_rare_elite" to Color.RED, "elite_silver" to Color.CYAN, "elite_gold" to Color.MAGENTA)) {
            val b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(col) }
            File(kit, "$n.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return root
    }

    private fun spot(kind: UiKit.Spawn, size: Float, live: Boolean): Bitmap {
        val b = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        UiKit.spawnSpot(Canvas(b), 40f, 40f, kind, size, live = live)
        return b
    }

    private fun count(b: Bitmap, pred: (Int) -> Boolean): Int {
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px.count { Color.alpha(it) > 0 && pred(it) }
    }
    private val red = { p: Int -> Color.red(p) > 150 && Color.green(p) < 90 && Color.blue(p) < 90 }
    /** the flat silver (cyan), also through the ghosts' pale paint (saturation 0.35: red ~130, green / blue ~220) */
    private val cyan = { p: Int -> Color.green(p) > 150 && Color.blue(p) > 150 && Color.red(p) < Color.green(p) - 60 }

    @Test fun aRareAndARareEliteWearTheSilverDragonNotTheStar() {
        art(flatKit())
        for (kind in listOf(UiKit.Spawn.RARE, UiKit.Spawn.RARE_ELITE)) for (live in listOf(true, false)) {
            val b = spot(kind, 44f, live)
            assertEquals("$kind live=$live: no old vignette mark", 0, count(b, red))
            assertTrue("$kind live=$live: the silver dragon (${count(b, cyan)} px)", count(b, cyan) > 400)
        }
    }

    @Test fun theZoneZoomDotIsSilver() {
        art(flatKit())
        val b = spot(UiKit.Spawn.RARE, 12f, live = false)
        val silverish = count(b) { p -> Color.blue(p) >= Color.red(p) && Color.red(p) > 90 && Math.abs(Color.green(p) - Color.blue(p)) < 30 }
        assertEquals("no maroon", 0, count(b, red))
        assertTrue("a silver dot ($silverish px)", silverish > 20)
    }

    @Test fun noKitDrawsTheSilverStandIn() {
        art(null)
        val b = spot(UiKit.Spawn.RARE_ELITE, 44f, live = true)
        assertTrue("the stand-in's silver", count(b) { it == Color.rgb(200, 205, 214) } > 20)
        assertEquals("no gold on a rare", 0, count(b) { it == Color.rgb(232, 184, 60) })
    }

    private fun doc() = JSONObject("""{"type":"map","ok":true,"mapID":1426,"zone":"Dun Morogh","subzone":"","x":0.5,"y":0.5,"cont":0,
        "wx":-5500.0,"wy":0.0,"facing":0.1,"rect":{"c":0,"t":-3500.0,"b":-7500.0,"l":2000.0,"r":-2000.0}}""")

    /** a rare and a rare elite (cmangos ranks 4 / 2) [d] off the player (map units: Home's close-in map ~0.005, the zone ~0.04) */
    private fun pins(d: Double) = JSONObject("""{"type":"qdbpins","m":1426,"set":7,"svc":[],
        "rares":[{"id":1,"n":"Timber","r":4,"p":[[${0.5 + d},${0.5 - d}]]},{"id":2,"n":"Old Icebeard","r":2,"p":[[${0.5 - d},${0.5 + d}]]}]}""")

    private fun settle(ms: Long = 1500) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) { Thread.sleep(20); ShadowLooper.idleMainLooper() }
    }

    private fun render(v: View, w: Int, h: Int): Bitmap {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        return b
    }

    /** Home's small map, close in (its own zoom, the rares' switch on: nothing another test left decides what draws) */
    private fun homeMap(): MapView = MapView(app).apply {
        compact = true
        MapView::class.java.getDeclaredField("zoom").apply { isAccessible = true }.setFloat(this, 3.0f)
        showRares = true
        update(doc(), 0); onQdbPins(pins(0.005))
    }

    @Test fun homesMapDrawsItsRaresSilver() {
        art(flatKit())
        val v = homeMap()
        val b = render(v, 520, 420)
        System.getenv("MAPRARE_SHOTS_DIR")?.let { d -> File(d).mkdirs(); File(d, "home_map_flatkit.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        assertEquals("no old vignette mark on Home's map", 0, count(b, red))
        // the ghost (pale, see-through) over the dark field: a dim teal from the flat cyan
        val teal = { p: Int -> Color.green(p) > 100 && Color.blue(p) > 100 && Color.red(p) < Color.green(p) - 30 && Math.abs(Color.green(p) - Color.blue(p)) < 20 }
        assertTrue("the rares silver on Home's map (${count(b, teal)} px)", count(b, teal) > 400)
    }

    @Test fun shots() {
        val dir = System.getenv("MAPRARE_SHOTS_DIR")?.let { File(it) } ?: return
        val root = System.getenv("MAPRARE_ART_ROOT")?.let { File(it) } ?: return
        dir.mkdirs()
        art(root)
        MinimapTiles.refresh()
        val home = homeMap()
        render(home, 520, 420); settle()
        val hb = render(home, 520, 420)
        File(dir, "home_map_rares.png").outputStream().use { hb.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        // the Map room's Rares filter on (Show: menu; its own prefs)
        act.getSharedPreferences("maproom", android.content.Context.MODE_PRIVATE).edit().putBoolean("rare", true).commit()
        val room = WorldMapRoomView(act)
        act.setContentView(room)
        room.loadArt()
        val ready = WorldMapRoomView::class.java.getDeclaredField("artReady").apply { isAccessible = true }
        val until = System.currentTimeMillis() + 10_000L
        while (ready.get(room) != true && System.currentTimeMillis() < until) { ShadowLooper.idleMainLooper(); Thread.sleep(10) }
        room.update(doc()); room.onQdbPins(pins(0.04))
        room.measure(View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
        room.layout(0, 0, 1240, 1000)
        room.onShown()
        val rb = Bitmap.createBitmap(1240, 1000, Bitmap.Config.ARGB_8888)
        val pic = System.currentTimeMillis() + 5_000L
        do {
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(16)); ShadowLooper.idleMainLooper(); Thread.sleep(10)
            room.draw(Canvas(rb))
        } while (room.pictureBytes == 0L && System.currentTimeMillis() < pic)
        repeat(4) { org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(16)); ShadowLooper.idleMainLooper(); room.draw(Canvas(rb)) }
        File(dir, "maproom_rares.png").outputStream().use { rb.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
