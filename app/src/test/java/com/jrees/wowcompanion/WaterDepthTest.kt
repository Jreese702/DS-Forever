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
    private val ROOF = Color.rgb(64, 96, 144)
    private val COAST = 31.7

    private fun setRoot(root: File, cache: File) {
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, root)
        MinimapTiles.refresh(); TerrainOverview.refresh(); TerrainOverview.load()
        TerrainOverview.dir = cache
        WaterDepth::class.java.getDeclaredField("fields").apply { isAccessible = true }.let { (it.get(WaterDepth) as MutableMap<*, *>).clear() }
    }

    /** the made-up continent 1 (ADTs 27..[lastCol] x 27..37) */
    private fun madeUp(lastCol: Int = 37): File {
        val root = File("build/tmp/wd-art-v2-$lastCol").absoluteFile
        val dir = File(root, "minimap")
        if (!File(dir, "index.json").isFile) {
            val k = JSONArray()
            for (col in 27..lastCol) for (row in 27..37) {
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
                if (col == 28 && row == 31) {
                    // a blue-roofed house on the sand (Goldshire's Lion's Pride Inn, (64, 96, 128) .. (96, 128, 176)) with a shaded edge,
                    // and a lake with light shallows round a dark middle (Loch Modan)
                    p.color = ROOF; c.drawRect(100f, 100f, 180f, 150f, p)
                    p.color = Color.rgb(40, 72, 104); c.drawRect(100f, 150f, 180f, 154f, p)
                    p.color = Color.rgb(70, 110, 120); c.drawCircle(360f, 360f, 90f, p)
                    p.color = LAKE; c.drawCircle(360f, 360f, 70f, p)
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
        WaterDepth.recolourTile(b, cont, col, row)
        return b
    }
    private fun raw(cont: Int, col: Int, row: Int): Bitmap =
        BitmapFactory.decodeFile(ArtHarvest.file("minimap/$cont/${col}_$row.webp").path,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565; inMutable = true })

    private fun diff(a: Int, b: Int) = maxOf(kotlin.math.abs(Color.red(a) - Color.red(b)), kotlin.math.abs(Color.green(a) - Color.green(b)),
        kotlin.math.abs(Color.blue(a) - Color.blue(b)))
    /** the step across the border between [l] (west) and [r] (east) per 8 px row band: the border's two px columns (averaged down the
     *  band: the dither out) less the larger step one px inside either tile (a coast running through the border steps there too) */
    private fun seamRows(l: Bitmap, r: Bitmap): IntArray {
        val rows = ArrayList<Int>()
        for (y in 0 until l.height - 7 step 8) {
            fun avg(b: Bitmap, x: Int): IntArray { val s = IntArray(3); for (yy in y until y + 8) { val p = b.getPixel(x, yy); s[0] += Color.red(p); s[1] += Color.green(p); s[2] += Color.blue(p) }; return IntArray(3) { s[it] / 8 } }
            val a = avg(l, l.width - 1); val b = avg(r, 0)
            val a2 = avg(l, l.width - 2); val b2 = avg(r, 1)
            var worst = 0
            for (ch in 0..2) worst = maxOf(worst, kotlin.math.abs(a[ch] - b[ch]) - maxOf(kotlin.math.abs(a[ch] - a2[ch]), kotlin.math.abs(b2[ch] - b[ch])))
            rows.add(worst)
        }
        return rows.toIntArray()
    }
    /** the largest step across the border between [l] (west) and [r] (east) */
    private fun seam(l: Bitmap, r: Bitmap): Int = seamRows(l, r).maxOrNull() ?: 0

    @Test fun no_seams_at_tile_borders_deep_far_out_shallow_at_the_coast() {
        setRoot(madeUp(), File("build/tmp/wd-cache-m").absoluteFile.also { it.deleteRecursively() })
        // no field (none to be had): which water is the ocean is not known - the art as it is (ocean only, 2026-11-17)
        WaterDepth::class.java.getDeclaredField("none").apply { isAccessible = true }.let { @Suppress("UNCHECKED_CAST") (it.get(WaterDepth) as MutableSet<Int>).add(1) }
        val p31 = tile(1, 31, 32)!!
        assertEquals("no field: nothing repainted", raw(1, 31, 32).getPixel(420, 256), p31.getPixel(420, 256))
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
        println("WD seams: final 31|32 $s1, 32|33 $s2, 33|34 $s3, row 32|33 $ns")
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

    @Test fun land_stays_solid_dark_woods_rock_crevices_and_a_lake_is_the_art() {
        setRoot(madeUp(), File("build/tmp/wd-cache-m2").absoluteFile.also { it.deleteRecursively() })
        WaterDepth.fieldNow(1)
        val r = raw(1, 29, 30); val t = tile(1, 29, 30)!!
        for ((name, x, y) in listOf(Triple("dark woods (R+G+B 94)", 60, 60), Triple("dark green", 380, 60), Triple("grey rock", 380, 200),
                Triple("sand by the crevices", 315, 400), Triple("a crevice (3 px black in the land)", 301, 400)))
            assertEquals("$name untouched", r.getPixel(x, y), t.getPixel(x, y))
        var crev = 0
        for (i in 0 until 6) for (y in 310 until 470 step 10) if (t.getPixel(301 + i * 30, y) != r.getPixel(301 + i * 30, y)) crev++
        assertEquals("no crevice pixel repainted", 0, crev)
        // the lake (inside land, 70 px = 73 yd radius): not the ocean - the game's own colours (the user, 2026-11-17: "I don't know why
        // we painted any of the actual maps and not just only the surrounding ocean")
        var lake = 0
        for (y in 320 until 450 step 4) for (x in 64 until 192 step 4) if (t.getPixel(x, y) != r.getPixel(x, y)) lake++
        assertEquals("the lake is the art's", 0, lake)
    }

    /** review of 1.0.34 test 1485: blue roofs read as teal water became turquoise pools with a shoreline (Goldshire). A roof stays the
     *  art's; and (ocean only) a lake with light shallows too */
    @Test fun blue_roofs_and_a_light_shallowed_lake_stay_the_art() {
        setRoot(madeUp(), File("build/tmp/wd-cache-m4").absoluteFile.also { it.deleteRecursively() })
        WaterDepth.fieldNow(1)
        for (sample in listOf(1, 4)) {
            val r = BitmapFactory.decodeFile(ArtHarvest.file("minimap/1/28_31.webp").path,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565; inSampleSize = sample })
            val t = tile(1, 28, 31, sample)!!
            var changed = 0; var all = 0
            for (y in 100 / sample until 154 / sample) for (x in 100 / sample until 180 / sample) { all++; if (t.getPixel(x, y) != r.getPixel(x, y)) changed++ }
            assertEquals("sample $sample: the blue roof is untouched ($changed of $all px repainted)", 0, changed)
            val mid = t.getPixel(360 / sample, 360 / sample); val rim = t.getPixel(360 / sample, 280 / sample)
            assertEquals("sample $sample: the lake is the art's", r.getPixel(360 / sample, 360 / sample), mid)
            assertEquals("sample $sample: its light shallows too", r.getPixel(360 / sample, 280 / sample), rim)
        }
    }

    /** the same on the real minimap (MK_ART_ROOT): Goldshire's roofs (Elwynn (31, 49)) and Stormwind's ((30, 48)) stay the art's;
     *  OCEAN ONLY (the user, 2026-11-17: "The water in stormwind looks AWFUL"): Stormwind's canals and Loch Modan's lake ((38, 42)) are
     *  the game's own art, every pixel; the harbour's open sea west of the city ((29, 48)) still has its depth */
    @Test fun real_blue_roofs_city_canals_and_lakes_stay_the_art() {
        if (realArt == null) return
        setRoot(File(realArt), File("build/tmp/wd-cache-r").absoluteFile)
        WaterDepth.fieldNow(0)
        fun bright(p: Int) = Color.red(p) + Color.green(p) + Color.blue(p) >= 260 && Color.blue(p) > Color.red(p) + 40 && Color.blue(p) > Color.green(p)
        for ((col, row) in listOf(31 to 49, 30 to 48)) {
            val r = raw(0, col, row); val t = tile(0, col, row)!!
            var roof = 0; var kept = 0
            for (y in 0 until 512) for (x in 0 until 512) if (bright(r.getPixel(x, y))) { roof++; if (t.getPixel(x, y) == r.getPixel(x, y)) kept++ }
            println("WD roofs ($col, $row): $kept of $roof bright-blue px kept")
            assertTrue("($col, $row) has blue roofs ($roof px)", roof > 200)
            assertTrue("($col, $row): the blue roofs stay buildings ($kept of $roof px kept)", kept * 100 >= roof * 97)
        }
        fun teal(name: String, col: Int, row: Int): IntArray {
            val r = raw(0, col, row); val t = tile(0, col, row)!!
            var water = 0; var painted = 0; var all = 0
            for (y in 0 until 512 step 2) for (x in 0 until 512 step 2) { val p = r.getPixel(x, y); all++; if (t.getPixel(x, y) != p) painted++
                if (WaterDepth.waterW(Color.red(p), Color.green(p), Color.blue(p)) >= 0.9f) water++ }
            println("WD $name ($col, $row): $water water px, $painted of $all px repainted")
            return intArrayOf(water, painted)
        }
        val loch = teal("Loch Modan", 38, 42)
        assertTrue("Loch Modan has its lake (${loch[0]})", loch[0] > 1000)
        assertEquals("Loch Modan's lake is the art's", 0, loch[1])
        // the city's canals: Stormwind's tiles east of the harbour, every pixel the art's
        for ((col, row) in listOf(30 to 47, 31 to 47, 30 to 48, 31 to 48)) {
            val c = teal("Stormwind", col, row)
            assertEquals("Stormwind ($col, $row): the canals are the art's", 0, c[1])
        }
        val harbour = teal("Stormwind harbour", 29, 48)
        assertTrue("the harbour's open sea keeps its depth (${harbour[1]} px repainted)", harbour[1] > 1000)
    }

    /** OCEAN ONLY (the user, 2026-11-17): the field's ocean is the water joined to the open sea through a wide channel. A 40 x 30 field:
     *  land from x = 12, the open sea west of it (x < 4 has no ADT); a bay (wide mouth) is ocean; a canal joined to the sea by a 2-cell
     *  gap, a lake and a city's ring of water are not */
    @Test fun the_ocean_is_the_water_joined_to_the_open_sea_through_a_wide_channel() {
        val w = 40; val h = 30
        val d = ByteArray(w * h) { if (it % w < 12) 0xFF.toByte() else 0 }
        fun water(x0: Int, y0: Int, x1: Int, y1: Int) { for (y in y0..y1) for (x in x0..x1) d[y * w + x] = 0xFF.toByte() }
        water(12, 2, 20, 9)          // a bay: 8 cells wide at its mouth
        water(12, 15, 13, 16)        // a 2-cell gap ...
        water(14, 13, 30, 18)        // ... into a canal basin
        water(25, 22, 32, 27)        // a lake
        val o = WaterDepth.oceanMask(d, w, h) { x, _ -> x < 4 }
        fun at(x: Int, y: Int) = o[y * w + x]
        assertTrue("the open sea", at(1, 1) && at(8, 20))
        assertTrue("the coast's own water (grown back)", at(11, 20))
        assertTrue("the bay", at(17, 5) && at(20, 9))
        assertFalse("the canal basin past a narrow gap", at(22, 15))
        assertFalse("the lake", at(28, 25))
        assertFalse("land", at(25, 5))
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


    /** review 3: the gradient ran on in the tiles to their last ADT and stopped there on a straight line, the flat fill beyond. Here
     *  the continent ends 1.3 ADTs (700 yd) off its coast, mid-gradient: the field's sea picture under the tiles carries it on, the
     *  tile's last column and the sea picture's first agree */
    @Test fun no_edge_where_the_tiles_end_and_the_sea_picture_begins() {
        setRoot(madeUp(lastCol = 32), File("build/tmp/wd-cache-e").absoluteFile.also { it.deleteRecursively() })
        val f = WaterDepth.fieldNow(1)!!
        val sea = f.sea!!
        val t = tile(1, 32, 32)!!
        assertTrue("final", WaterDepth.lastFinal)
        // the tile's east edge (col 33.0) and the sea picture just past it (8 px an ADT: its px (33 - c0) * 8), rows averaged
        var worst = 0
        val sx = (33 - f.c0) * 8
        for (row in 4 until 28 step 4) {             // (the tile's px rows row * 16; the sea picture's (32 - r0) * 8 + row / 4)
            var tr = 0; var tg = 0; var tb = 0
            for (x in 508 until 512) for (yy in row * 16 until row * 16 + 4) { val p = t.getPixel(x, yy); tr += Color.red(p); tg += Color.green(p); tb += Color.blue(p) }
            var sr = 0; var sg = 0; var sb = 0
            for (x in sx until sx + 2) { val p = sea.getPixel(x, minOf(sea.height - 1, (32 - f.r0) * 8 + row * 16 * 8 / 512)); sr += Color.red(p); sg += Color.green(p); sb += Color.blue(p) }
            worst = maxOf(worst, kotlin.math.abs(tr / 16 - sr / 2), kotlin.math.abs(tg / 16 - sg / 2), kotlin.math.abs(tb / 16 - sb / 2))
        }
        val mid = sea.getPixel(sx + 1, (32 - f.r0) * 8 + 4)
        println("WD tile | sea picture: worst $worst, sea there ${Integer.toHexString(mid)}")
        assertTrue("the gradient still runs there (not the deep colour yet: ${Integer.toHexString(mid)})", diff(mid, WaterDepth.DEEP) > 8)
        assertTrue("no edge where the tile ends and the sea picture begins ($worst)", worst <= 8)
    }

    // ---------------------------------------------------------------- review 3 on the real minimap (MK_ART_ROOT)
    /** the steps across the border between [a] (north) and [b] (south), the same way as [seamRows] */
    private fun seamRowsNS(a: Bitmap, b: Bitmap): IntArray {
        val ra = Bitmap.createBitmap(a.height, a.width, Bitmap.Config.ARGB_8888); val rb = Bitmap.createBitmap(b.height, b.width, Bitmap.Config.ARGB_8888)
        for (y in 0 until a.height) for (x in 0 until a.width) { ra.setPixel(y, x, a.getPixel(x, y)); rb.setPixel(y, x, b.getPixel(x, y)) }
        return seamRows(ra, rb)
    }

    @Test fun real_coast_no_seams_no_black_fringe_no_edge_strip() {
        if (realArt == null) return
        setRoot(File(realArt), File("build/tmp/wd-cache-r").absoluteFile)
        WaterDepth.fieldNow(1); WaterDepth.fieldNow(0)
        // the coast tiles' borders, full size (Durotar's fringe; the reviewer's cuts at the z3 view) and at a quarter
        val report = StringBuilder()
        for (sample in listOf(1, 4)) {
            var worst = 0
            // (the step the repaint ADDS: the art's own step at a border - land's texture, or a blue ADT beside a black one - is not ours,
            // but where the art's step is the sea's own colour change, the repaint must take it away, not keep it)
            fun rawS(cont: Int, col: Int, row: Int) = BitmapFactory.decodeFile(ArtHarvest.file("minimap/$cont/${col}_$row.webp").path,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565; inSampleSize = sample })
            fun added(a: IntArray, b: IntArray) = a.indices.maxOf { a[it] - b[it] }
            fun ew(cont: Int, col: Int, row: Int): Int = added(seamRows(tile(cont, col, row, sample)!!, tile(cont, col + 1, row, sample)!!),
                seamRows(rawS(cont, col, row), rawS(cont, col + 1, row)))
            fun ns(cont: Int, col: Int, row: Int): Int = added(seamRowsNS(tile(cont, col, row, sample)!!, tile(cont, col, row + 1, sample)!!),
                seamRowsNS(rawS(cont, col, row), rawS(cont, col, row + 1)))
            fun note(name: String, v: Int) { report.append(" $name=$v"); worst = maxOf(worst, v) }
            for (row in 29..33) note("k41|42@$row", ew(1, 41, row))
            for (row in 29..33) note("k42|43@$row", ew(1, 42, row))
            for (row in 29..32) note("k42@$row/${row + 1}", ns(1, 42, row))
            for (row in 29..32) note("k43@$row/${row + 1}", ns(1, 43, row))
            // Westfall: the dark sea of (27, 53) beside the blue of (28, 53) (the reviewer's dark block)
            note("e27|28@53", ew(0, 27, 53))
            note("e30@53/54", ns(0, 30, 53))
            report.append(" sample $sample: worst $worst")
            println("WD real seams$report")
            // (the reviewer's cut was ~190: (8, 16, 0) beside (131, 197, 188); what is left is a coast's own contrast where it runs
            // through a border, the dither, a cell's rounding)
            assertTrue("no seam added at the coast's tile borders (sample $sample: $worst)", worst <= 32)
            // the art's own seam south of Westfall: (29, 53)'s blue sea above (29, 54)'s green seabed; the seabed by the coast stays
            // partly seabed-green, so its edge on the ADT line is the art's - in the report, held under the art's own blue | green step
            val e29 = ns(0, 29, 53)
            report.append(" e29@53/54=$e29")
            println("WD real seams$report")
            assertTrue("south of Westfall: the art's own seam, not ours ($e29)", e29 <= 80)
        }
        // no black fringe: Durotar's coast tiles have no near-black left (the open sea and the fringe are all repainted)
        for ((col, row) in listOf(42 to 30, 42 to 31, 42 to 32)) {
            val t = tile(1, col, row)!!
            var dark = 0; var all = 0
            for (y in 0 until 512 step 2) for (x in 0 until 512 step 2) { val p = t.getPixel(x, y); if (maxOf(Color.red(p), Color.green(p), Color.blue(p)) < 32) dark++; all++ }
            report.append(" ($col,$row) near-black ${100.0 * dark / all}%")
            assertTrue("no near-black fringe left in ($col, $row): ${100.0 * dark / all}%", dark * 100 < all)
        }
        // the world's edge strip (Kalimdor (45..47, 20): flat ground (75, 72, 46) along the side facing no ADT) is water, not a line
        for (col in 45..47) {
            val r = raw(1, col, 20); val t = tile(1, col, 20)!!
            val p = t.getPixel(256, 10)
            assertTrue("the raw strip is there (${Integer.toHexString(r.getPixel(256, 10))})", Color.red(r.getPixel(256, 10)) > 60)
            assertTrue("($col, 20)'s edge strip repainted as water (${Integer.toHexString(p)})", Color.blue(p) > Color.red(p) + 30)
        }
        val f = WaterDepth.fieldNow(1)!!
        assertTrue("the field has sea at the strip (${f.yd(46.5, 20.02)} yd from land)", f.yd(46.5, 20.02) > 100f)
        println("WD real:$report")
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
        // ocean only (the user, 2026-11-17: "The water in stormwind looks AWFUL"): the city's canals and harbour-side water, Elwynn's
        // and Loch Modan's lakes as the game draws them
        region("stormwind_s2", 0, 29, 32, 46, 49, 2)
        region("stormwind_s1", 0, 30, 31, 47, 48, 1)
        region("loch_modan_s2", 0, 36, 40, 40, 43, 2)
        region("westfall_coast", 0, 25, 31, 49, 55, 4)
        region("elwynn_lakes", 0, 29, 36, 47, 51, 4)
        region("ashenvale", 1, 28, 40, 18, 28, 8)
        region("hyjal_full", 1, 36, 38, 24, 25, 1)
        region("durotar_full", 1, 43, 44, 30, 31, 1)
        region("durotar_seam_s4", 1, 41, 42, 30, 31, 4)
        region("durotar_seam_s1", 1, 41, 42, 30, 31, 1)
        region("seam_k41_42_r28_29_s1", 1, 41, 42, 28, 29, 1)
        region("seam_e29_r53_54_s1", 0, 29, 30, 53, 54, 1)
        region("seam_k41_42_r28_29_s4", 1, 41, 42, 28, 29, 4)
        region("seam_k42_43_r32_34_s4", 1, 42, 43, 32, 34, 4)
        // TerrainOverview's level 1 (2 ADTs a picture: what the other continent's view shows under its close tiles) over the same coast
        run {
            val bm = Bitmap.createBitmap(2 * 256, 3 * 256, Bitmap.Config.ARGB_8888)
            val c = Canvas(bm)
            for (oc in 20..21) for (or in 14..16) TerrainOverview.make(1, 1, oc, or)?.let {
                c.drawBitmap(it, null, Rect((oc - 20) * 256, (or - 14) * 256, (oc - 19) * 256, (or - 13) * 256), null) }
            File(out, "tiles_durotar_level1.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val t1 = System.nanoTime()
        for (i in 0 until 10) tile(1, 44, 31)
        println("WD repaint of a full 512 px coast tile: ${(System.nanoTime() - t1) / 10_000_000} ms (JVM)")
    }
}
