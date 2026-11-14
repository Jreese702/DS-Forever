package com.jrees.wowcompanion

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issues #207 #203 #212 #200 #208 (1.0.27, 8 GB Thors killed for memory): the background art's "never beside the game" pause runs from a
 * step's progress report, and groundtex / safepathzones / terrain reported once per ZONE - the game files (~450 MB native) stayed open
 * up to ~35 s after the game started while it grew to ~1.9 GB. Every per-tile game-file read in those steps (and seamless's per-zone
 * picture and per-tile write) is now a yield point; a read added later without one fails here. Review follow-up: safepathzones writes a
 * zone's ground only after its water, and seamless recycles its bitmaps when a pause throws.
 */
class ArtYieldPointsTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }

    /** the step object's body: from its id line to the end of its run() (the next line that closes the object at 4 spaces) */
    private fun body(file: String, id: String): List<String> {
        val lines = File(src, file).readLines()
        val start = lines.indexOfFirst { it.contains("override val id = \"$id\"") }
        assertTrue("$file: step $id not found", start >= 0)
        val end = (start + 1 until lines.size).first { lines[it] == "    }" }
        return lines.subList(start, end)
    }

    /** every game-file read in [b] (but the continent's WDT, once per continent) has a yield point on its line or the 4 before it */
    private fun everyReadYields(file: String, id: String) {
        val b = body(file, id)
        var reads = 0
        for ((k, l) in b.withIndex()) {
            if (!l.contains("g.read(") || l.contains("MAP_WDT")) continue
            reads++
            val window = b.subList(maxOf(0, k - 4), k + 1).joinToString("\n")
            assertTrue("$file $id: a read without a yield point: ${l.trim()}", window.contains("yieldPoint("))
        }
        assertTrue("$file $id: no per-tile reads found (the check is out of date)", reads > 0)
    }

    @Test fun groundtexYieldsBeforeEachTile() = everyReadYields("Roads.kt", "groundtex")

    @Test fun safepathZonesYieldBeforeEachTileAndTheWaterReads() {
        everyReadYields("SafePath.kt", "safepathzones")
        val b = body("SafePath.kt", "safepathzones").joinToString("\n")
        assertTrue(Regex("ZoneWater\\(lethal,").containsMatchIn(b))
        assertTrue("the water's three readers each yield", Regex("yieldPoint\\(at, rects.size\\)").findAll(b).count() >= 4)
    }

    @Test fun terrainReadsYield() {
        val b = body("Terrain.kt", "terrain").joinToString("\n")
        assertTrue(b.contains("fun read(fid: Int): ByteArray? { job.yieldPoint(at, count); return g.read(fid)"))
        assertTrue(b.contains("at = i"))
    }

    @Test fun seamlessYieldsPerZoneAndPerTile() {
        val all = File(src, "Seamless.kt").readText()
        assertTrue(all.contains("composite(art, model, rects, cont, variant) { job.yieldPoint("))
        assertTrue(all.contains("pyramid(big) { rel, b -> job.yieldPoint("))
        assertTrue(Regex("for \\(z in zonesOf\\(cont, model, rects\\)\\) \\{\\s*yieldPoint\\(\\)").containsMatchIn(all))
    }

    @Test fun safepathZonesWritesTheGroundOnlyAfterItsWater() {
        // review of #207: a pause mid-water must not leave zones/$map.bin without water/$map.bin (Ground's '?: true' = all water walkable)
        val b = body("SafePath.kt", "safepathzones").joinToString("\n")
        val water = b.indexOf("write(job.file(\"safepath/water/\$map.bin\"))")
        val wDrop = b.indexOf("waters.drop(map)")
        val zone = b.indexOf("z.write(job.file(\"safepath/zones/\$map.bin\"))")
        val zDrop = b.indexOf("zones.drop(map)")
        assertTrue("both writes found", water >= 0 && zone >= 0 && wDrop >= 0 && zDrop >= 0)
        assertTrue("the zone's ground is written after its water", zone > water && zone > wDrop && zDrop > zone)
        assertTrue("no second zones write", Regex("safepath/zones/").findAll(b).count() == 1)
    }

    @Test fun seamlessRecyclesItsPicturesWhenAPauseThrows() {
        // review of #207: the yield points throw PausedForGame mid-picture; the 43 MB continent bitmap (and the pyramid's level) must not leak
        val all = File(src, "Seamless.kt").readText()
        assertTrue("STEP recycles big in a finally", Regex("try \\{ pyramid\\(big\\)[\\s\\S]{0,300}?\\}\\s*finally \\{ big.recycle\\(\\) \\}").containsMatchIn(all))
        assertTrue("composite recycles on the throw path", all.contains("catch (t: Throwable) { zb?.recycle(); big.recycle(); throw t }"))
        assertTrue("pyramid recycles its level in a finally", all.contains("} finally { if (lv !== big) lv.recycle() }"))
        assertTrue("pyramid recycles the tile in hand", all.contains("try { write(\"\$level/\${cc}_\$r.webp\", t) } finally { if (t !== lv) t.recycle() }"))
    }

    @Test fun theYieldPointIsTheProgressReport() {
        // checkStop runs from the report: a yield point that only logged would not pause anything
        val a = File(src, "ArtHarvest.kt").readText()
        assertTrue(a.contains("fun yieldPoint(done: Int, total: Int, what: String = \"\") = progress(done, total, what)"))
        assertTrue(a.contains("Job(ctx, g, root(ctx), s.id, { f, what -> checkStop();"))
        // memory relief 2 (the user's picks, 2026-11-14: "within one file"): every Job also carries the per-file stop and the ask
        assertTrue(Regex("Job\\(ctx, g, root\\(ctx\\), s.id, \\{").findAll(a).count() ==
            Regex("stop = \\{ checkpoint\\(\\) \\}, asked = \\{ letGoAsked\\(\\) \\}\\)").findAll(a).count())
    }
}
