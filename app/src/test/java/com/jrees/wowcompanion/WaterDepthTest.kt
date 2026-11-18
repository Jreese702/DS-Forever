package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * WATER WITH DEPTH (the user, 2026-11-17: "I dont love it being one shade of teal. Can we give the water depth?"; the pick:
 * "Shore-to-deep gradient"). WaterDepth repaints each terrain picture's water by its distance to land once when it is made: no seams at
 * tile borders (the continent's field seeds every tile), land untouched (the dark woods too), deep far out, shallows at the coast, nothing
 * per frame. Made-up art: a continent whose coast runs north-south through ADT column 31 (land west of 31.7), black sea east of it, a
 * dark-woods block, a lake, and thin black crevices in the land. With MK_ART_ROOT, the real minimap's regions are repainted and drawn
 * to MK_SHOTS_DIR (the palette's look).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WaterDepthTest {
    private val out: File? = System.getenv("MK_SHOTS_DIR")?.let { File(it, "map-water-depth") }
    private val realArt = System.getenv("MK_ART_ROOT")

    private val SEA = Color.rgb(4, 6, 8)
    private val SAND = Color.rgb(150, 120, 70)
    /** Elwynn / Duskwood's darkest woods: R+G+B about 94 */
    private val WOODS = Color.rgb(30, 40, 24)
    private val DARK_GREEN = Color.rgb(19, 45, 16)
    private val GREY_ROCK = Color.rgb(70, 80, 85)
    private val LAKE = Color.rgb(24, 48, 58)
    private val COAST = 31.7

    private fun setRoot(root: File, cache: File) {
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, root)
        MinimapTiles.refresh(); TerrainOverview.refresh(); TerrainOverview.load()
        TerrainOverview.dir = cache
        WaterDepth::class.java.getDeclaredField("fields").apply { isAccessible = true }.let { (it.get(WaterDepth) as MutableMap<*, *>).clear() }
    }

    /** the made-up continent 1 (ADTs 27..37 x 27..37) */
    private fun madeUp(): File {
        val root = File("build/tmp/wd-art-v1").absoluteFile
        val dir = File(root, "minimap")
        if (!File(dir, "index.json").isFile) {
            val k = JSONArray()
            for (col in 27..37) for (row in 27..37) {
                val b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                val c = Canvas(b)
                val p = Paint()
                c.drawColor(SEA)
                // land west of column COAST
                val landPx = ((COAST - col) * 512).toFloat()
                if (landPx > 0f) { p.color = SAND; c.drawRect(0f, 0f, minOf(512f, landPx), 512f, p) }
                if (col == 29 && row == 30) {
                    // the dark woods, a lake inside them, thin black crevices in the sand
                    p.color = WOODS; c.drawRect(0f, 0f, 256f, 256f, p)
                    p.color = DARK_GREEN; c.drawRect(256f, 0f, 512f, 128f, p)
                    p.color = GREY_ROCK; c.drawRect(256f, 128f, 512f, 256f, p)
                    p.color = LAKE; c.drawCircle(128f, 384f, 70f, p)
                    p.color = Color.rgb(3, 3, 3)
                    for (i in 0 until 6) c.drawRect(300f + i * 30f, 300f, 303f + i * 30f, 480f, p)
                }
                val f = File(dir, "1/${col}_$row.webp"); f.parentFile.mkdirs()
                f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                k.put(JSONArray(listOf(col, row)))
            }
            File(dir, "index.json").writeText(JSONObject().put("px", 512).put("adt", 533.33333)
                .put("continents", JSONObject().put("1", JSONObject().put("dir", "kalimdor").put("tiles", k))).toString())
        }
        return root
    }

    @Before fun clean() {
        WaterDepth::class.java.getDeclaredField("none").apply { isAccessible = true }.let { (it.get(WaterDepth) as MutableSet<*>).clear() }
    }

    /** the tile as MinimapTiles makes it (decoded mutable RGB_565, repainted) */
    private fun tile(cont: Int, col: Int, row: Int, sample: Int = 1): Bitmap? {
        val f = ArtHarvest.file("minimap/$cont/${col}_$row.webp")
        if (!f.isFile) return null
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565; inSampleSize = sample; inMutable = true })
        WaterDepth.recolour(b, cont, col.toDouble(), row.toDouble(), 1.0)
        return b
    }
    private fun raw(cont: Int, col: Int, row: Int): Bitmap =
        BitmapFactory.decodeFile(ArtHarvest.file("minimap/$cont/${col}_$row.webp").path,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565; inMutable = true })

    private fun diff(a: Int, b: Int) = maxOf(kotlin.math.abs(Color.red(a) - Color.red(b)), kotlin.math.abs(Color.green(a) - Color.green(b)),
        kotlin.math.abs(Color.blue(a) - Color.blue(b)))
    /** the largest step across the border between [l] (west) and [r] (east), per row averaged over 4 px each side (the dither out) */
    private fun seam(l: Bitmap, r: Bitmap): Int {
        var worst = 0
        for (y in 8 until l.height - 8 step 8) {
            fun avg(b: Bitmap, x0: Int): IntArray { val s = IntArray(3); for (x in x0 until x0 + 4) for (yy in y until y + 4) { val p = b.getPixel(x, yy); s[0] += Color.red(p); s[1] += Color.green(p); s[2] += Color.blue(p) }; return IntArray(3) { s[it] / 16 } }
            val a = avg(l, l.width - 4); val b = avg(r, 0)
            // the step across the border, less the gradient's own step over 4 px (the one inside each tile)
            val a2 = avg(l, l.width - 8); val b2 = avg(r, 4)
            for (ch in 0..2) worst = maxOf(worst, kotlin.math.abs(a[ch] - b[ch]) - maxOf(kotlin.math.abs(a[ch] - a2[ch]), kotlin.math.abs(b2[ch] - b[ch])))
        }
        return worst
    }

    @Test fun no_seams_at_tile_borders_deep_far_out_shallow_at_the_coast() {
        setRoot(madeUp(), File("build/tmp/wd-cache-m").absoluteFile.also { it.deleteRecursively() })
        // provisional (no field yet): tile 32 (all water, its west edge 0.3 ADT = 160 yd from the coast in tile 31) does not know the land
        // across its border: deep at its edge, tile 31 mid-teal at its own: the seam this all exists for (the test can see one)
        WaterDepth::class.java.getDeclaredField("none").apply { isAccessible = true }.let { @Suppress("UNCHECKED_CAST") (it.get(WaterDepth) as MutableSet<Int>).add(1) }
        val p31 = tile(1, 31, 32)!!; val p32 = tile(1, 32, 32)!!
        val provisionalSeam = seam(p31, p32)
        WaterDepth::class.java.getDeclaredField("none").apply { isAccessible = true }.let { (it.get(WaterDepth) as MutableSet<*>).clear() }
        // the field
        val f = WaterDepth.fieldNow(1)
        assertNotNull("the field built", f)
        assertTrue("its sea picture", f!!.sea != null)
        val t31 = tile(1, 31, 32)!!; val t32 = tile(1, 32, 32)!!; val t33 = tile(1, 33, 32)!!; val t36 = tile(1, 36, 32)!!
        assertTrue("final", WaterDepth.lastFinal)
        val s1 = seam(t31, t32); val s2 = seam(t32, t33); val s3 = seam(t33, tile(1, 34, 32)!!)
        // north-south too (row 32 / 33)
        val n1 = tile(1, 32, 32)!!; val n2 = tile(1, 32, 33)!!
        var ns = 0
        for (x in 8 until 504 step 8) ns = maxOf(ns, diff(n1.getPixel(x, 511), n2.getPixel(x, 0)))
        println("WD seams: provisional $provisionalSeam; final 31|32 $s1, 32|33 $s2, 33|34 $s3, row 32|33 $ns")
        assertTrue("a seam without the field (the test sees one: $provisionalSeam)", provisionalSeam > 12)
        assertTrue("no seam 31|32 ($s1)", s1 <= 6)
        assertTrue("no seam 32|33 ($s2)", s2 <= 6)
        assertTrue("no seam 33|34 ($s3)", s3 <= 6)
        assertTrue("no seam north-south ($ns)", ns <= 10)
        // deep far out: tile 36's middle is 4.8 ADTs (2.5 km) from the coast
        val deep = t36.getPixel(256, 256)
        assertTrue("deep far from the coast (${Integer.toHexString(deep)})", diff(deep, WaterDepth.DEEP) <= 10)
        // the shallows at the coast: light turquoise just east of it (31.7 -> px 358 of tile 31), its shoreline brighter still
        val shore = t31.getPixel(362, 256); val off = t31.getPixel(420, 256)
        assertTrue("shallows at the coast are light turquoise (${Integer.toHexString(shore)})", Color.green(shore) >= 150 && Color.blue(shore) >= 140 && Color.red(shore) < Color.green(shore))
        assertTrue("brighter at the coast than 60 yd out", Color.green(shore) > Color.green(off))
        assertTrue("the gradient darkens outward (32's middle darker than 31's water)", Color.green(t32.getPixel(256, 256)) < Color.green(off))
        // land west of the coast untouched
        assertEquals("land untouched", raw(1, 31, 32).getPixel(100, 100), t31.getPixel(100, 100))
        // the field agrees with the tiles: 160 yd east of the coast
        val yd = f.yd(32.0, 32.5)
        assertTrue("the field's distance at tile 32's west edge (~160 yd: $yd)", yd in 120f..210f)
    }

    @Test fun land_stays_solid_dark_woods_rock_crevices_and_a_lake_gets_shallows() {
        setRoot(madeUp(), File("build/tmp/wd-cache-m2").absoluteFile.also { it.deleteRecursively() })
        WaterDepth.fieldNow(1)
        val r = raw(1, 29, 30); val t = tile(1, 29, 30)!!
        for ((name, x, y) in listOf(Triple("dark woods (R+G+B 94)", 60, 60), Triple("dark green", 380, 60), Triple("grey rock", 380, 200),
                Triple("sand by the crevices", 315, 400), Triple("a crevice (3 px black in the land)", 301, 400)))
            assertEquals("$name untouched", r.getPixel(x, y), t.getPixel(x, y))
        var crev = 0
        for (i in 0 until 6) for (y in 310 until 470 step 10) if (t.getPixel(301 + i * 30, y) != r.getPixel(301 + i * 30, y)) crev++
        assertEquals("no crevice pixel repainted", 0, crev)
        // the lake (inside land, 70 px = 73 yd radius): repainted, a shallow turquoise, its shore lighter
        val mid = t.getPixel(128, 384)
        assertTrue("the lake is repainted (${Integer.toHexString(mid)})", mid != r.getPixel(128, 384) && Color.green(mid) >= 120)
        // tiny ponds may stay all shallow, but it is not the deep colour
        assertTrue("the lake is not the deep colour", diff(mid, WaterDepth.DEEP) > 40)
    }

    @Test fun repaint_once_scratch_reused_and_memory_small() {
        setRoot(madeUp(), File("build/tmp/wd-cache-m3").absoluteFile.also { it.deleteRecursively() })
        WaterDepth.fieldNow(1)
        tile(1, 31, 32)
        fun sf(n: String) = WaterDepth::class.java.getDeclaredField(n).apply { isAccessible = true }.get(WaterDepth)
        val a = sf("gRamp"); val b = sf("band")
        tile(1, 32, 32, 4); tile(1, 33, 32, 8); tile(1, 34, 32)
        assertSame("the scratch is reused (no allocation per picture)", a, sf("gRamp"))
        assertSame(b, sf("band"))
        // a land-only tile: nothing repainted (the fast path)
        tile(1, 28, 32)
        assertEquals("a land tile: no water cell", 0, WaterDepth.lastWater)
        val bytes = WaterDepth.bytes()
        println("WD memory (made-up, 1 field ${WaterDepth.fieldNow(1)!!.bytes} B): ${bytes} B")
        assertTrue("WaterDepth holds under 1.5 MB ($bytes)", bytes < 1_500_000L)
    }

    // ---------------------------------------------------------------- the real minimap (MK_ART_ROOT): the palette's look
    private fun region(name: String, cont: Int, c0: Int, c1: Int, r0: Int, r1: Int, sample: Int) {
        val px = 512 / sample
        val bm = Bitmap.createBitmap((c1 - c0 + 1) * px, (r1 - r0 + 1) * px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        val f = WaterDepth.fieldNow(cont)
        c.drawColor(WaterDepth.DEEP)
        f?.sea?.let { sb ->
            val s = px.toFloat()
            c.drawBitmap(sb, null, android.graphics.RectF((f.c0 - c0) * s, (f.r0 - r0) * s, (f.c0 - c0 + f.w / WaterDepth.FIELD_PX) * s,
                (f.r0 - r0 + f.h / WaterDepth.FIELD_PX) * s), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        for (col in c0..c1) for (row in r0..r1) tile(cont, col, row, sample)?.let { c.drawBitmap(it, null, Rect((col - c0) * px, (row - r0) * px, (col - c0 + 1) * px, (row - r0 + 1) * px), null) }
        out!!.mkdirs()
        File(out, "tiles_$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun real_regions() {
        if (realArt == null || out == null) return
        setRoot(File(realArt), File("build/tmp/wd-cache-r").absoluteFile)
        val t0 = System.nanoTime()
        val k = WaterDepth.fieldNow(1); val e = WaterDepth.fieldNow(0)
        println("WD fields: Kalimdor ${k?.w}x${k?.h} ${k?.bytes} B, Eastern Kingdoms ${e?.w}x${e?.h} ${e?.bytes} B, ${(System.nanoTime() - t0) / 1_000_000} ms; all ${WaterDepth.bytes()} B")
        region("durotar_coast", 1, 40, 47, 27, 36, 4)
        region("westfall_coast", 0, 25, 31, 49, 55, 4)
        region("elwynn_lakes", 0, 29, 36, 47, 51, 4)
        region("ashenvale", 1, 28, 40, 18, 28, 8)
        region("hyjal_full", 1, 36, 38, 24, 25, 1)
        region("durotar_full", 1, 43, 44, 30, 31, 1)
        region("durotar_seam_s4", 1, 41, 42, 30, 31, 4)
        region("durotar_seam_s1", 1, 41, 42, 30, 31, 1)
        val t1 = System.nanoTime()
        for (i in 0 until 10) tile(1, 44, 31)
        println("WD repaint of a full 512 px coast tile: ${(System.nanoTime() - t1) / 10_000_000} ms (JVM)")
    }
}
