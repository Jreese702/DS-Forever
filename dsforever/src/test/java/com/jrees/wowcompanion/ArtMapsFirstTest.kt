package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issues #243 #262 #263 #264 (1.0.29: "the world map pictures wont load", "Maps just will not download", "i cant see my map"): every
 * report's Game art section showed "allicons v2 (background): PENDING; this run: running" with worldmap / minimap never stamped. The
 * all-icons step kept its old place ahead of the maps after it moved to the background (1.0.17), and the background art only runs
 * while the game is closed (PlayGate). The maps go first now; the Map room reads a world map built while it was open; its note says
 * when the pictures build; and both map steps yield before each picture (the launch meets them now).
 */
class ArtMapsFirstTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val assets = File(src, "../../../../assets/companion/i18n")

    /** ArtHarvest.STEPS' ids as the base build lists them (KitSteps, AllIconsStep, MapHarvestSteps.STEPS, IndoorSchedulerStep) */
    private val base = listOf("kit", "cast", "icons", "allicons", "worldmap", "poiicons", "npcicons", "minimap", "transports",
        "travelicons", "wmominimap", "areas", "terrain", "groundtex", "seamless", "safepathzones", "climb", "interiors")

    @Test fun theMapsRunBeforeTheAllIconsPacks() {
        val got = ArtHarvest.mapsFirst(base) { it }
        assertEquals(listOf("kit", "cast", "icons", "worldmap", "minimap", "allicons", "poiicons", "npcicons", "transports",
            "travelicons", "wmominimap", "areas", "terrain", "groundtex", "seamless", "safepathzones", "climb", "interiors"), got)
        assertEquals("nothing lost or doubled", base.sorted(), got.sorted())
    }

    @Test fun everyOtherStepKeepsItsOrder() {
        val got = ArtHarvest.mapsFirst(base) { it }.filter { it !in ArtHarvest.MAPS_FIRST }
        assertEquals(base.filter { it !in ArtHarvest.MAPS_FIRST }, got)
    }

    @Test fun nothingMovesWithoutBothSides() {
        val noIcons = base - "allicons"
        assertEquals(noIcons, ArtHarvest.mapsFirst(noIcons) { it })
        val noMaps = base - ArtHarvest.MAPS_FIRST.toSet()
        assertEquals(noMaps, ArtHarvest.mapsFirst(noMaps) { it })
        // the two always come in this order: the world map, then Home's minimap
        assertEquals(listOf("minimap", "worldmap", "allicons").let { ArtHarvest.mapsFirst(it) { s -> s } },
            listOf("worldmap", "minimap", "allicons"))
        assertEquals(listOf("icons", "minimap", "allicons", "seamless"),
            ArtHarvest.mapsFirst(listOf("icons", "allicons", "minimap", "seamless")) { it })
    }

    @Test fun theRealStepListGoesThroughIt() {
        val a = File(src, "ArtHarvest.kt").readText()
        assertTrue(a.contains("mapsFirst(listOf<Step>(KitSteps.Kit, KitSteps.Cast, KitSteps.Icons, AllIconsStep) + MapHarvestSteps.STEPS + IndoorSchedulerStep) { it.id }"))
        // the ids it moves are the steps' own
        val m = File(src, "MapHarvestSteps.kt").readText()
        for (id in ArtHarvest.MAPS_FIRST) assertTrue("step $id exists", m.contains("override val id = \"$id\""))
        assertTrue(File(src, "AllIconsStep.kt").readText().contains("override val id = \"allicons\""))
    }

    @Test fun aStepDoneReachesEveryListener() {
        val seen = ArrayList<String>()
        val bad: (String) -> Unit = { error("a listener that throws") }
        val good: (String) -> Unit = { seen.add(it) }
        ArtHarvest.onStepDone(bad); ArtHarvest.onStepDone(good)
        try {
            ArtHarvest.stepFinished("worldmap")
            ArtHarvest.stepFinished("minimap")
        } finally { ArtHarvest.offStepDone(bad); ArtHarvest.offStepDone(good) }
        assertEquals(listOf("worldmap", "minimap"), seen)
        ArtHarvest.stepFinished("worldmap")
        assertEquals("gone after offStepDone", 2, seen.size)
    }

    @Test fun bothStampPathsTellTheListeners() {
        val a = File(src, "ArtHarvest.kt").readText()
        assertTrue("the re-stamp path", Regex("stepLog\\[s.id\\] = \"unchanged \\(re-stamped\\)\"; before \\+= w\\s*stepFinished\\(s.id\\)\\s*continue").containsMatchIn(a))
        val built = a.indexOf("markDone(ctx, s, g.buildKey, job.inputKey)")
        val told = a.indexOf("stepFinished(s.id)", built)
        assertTrue("told after the stamp", built > 0 && told > built)
    }

    @Test fun theMapRoomReadsAWorldMapBuiltWhileItWasOpen() {
        val h = File(src, "CompanionHost.kt").readText()
        assertTrue(h.contains("if (id == \"worldmap\") ui.post { if (::worldMapRoom.isInitialized) worldMapRoom.reloadArtIfMissing() }"))
        assertTrue(h.contains("ArtHarvest.onStepDone(mapArtDone)"))
        assertTrue(h.contains("ArtHarvest.offStepDone(mapArtDone)"))
        val v = File(src, "WorldMapRoomView.kt").readText()
        assertTrue(v.contains("fun reloadArtIfMissing() { if (!artReady) loadArt() }"))
    }

    @Test fun theNoteSaysWhenThePicturesBuildInEveryLanguage() {
        val v = File(src, "WorldMapRoomView.kt").readText()
        val note = Regex("const val NO_ART_NOTE = \"([^\"]+)\"").find(v)?.groupValues?.get(1)
        assertEquals("Map pictures build while the game is closed", note)
        assertTrue(v.contains("} catch (t: Throwable) { NO_ART_NOTE }"))
        assertTrue("drawn through Tr", v.contains("c.drawText(Tr.t(artNote ?: \"Opening the map\")"))
        for (lang in listOf("es", "fr")) {
            val f = File(assets, "$lang.json")
            assertTrue("$f", f.isFile)
            val plain = org.json.JSONObject(f.readText()).getJSONObject("plain")
            assertTrue("$lang has the note", plain.optString(note!!).isNotBlank())
            assertTrue("$lang keeps CRLF", f.readText().contains("\"$note\": \"${plain.getString(note)}\",\r\n"))
        }
    }

    // ------------------------------------------------------------------ the yield points (#207's rule) on the two map steps

    /** the step object's body: from its id line to the line that closes the object at 4 spaces */
    private fun body(id: String): List<String> {
        val lines = File(src, "MapHarvestSteps.kt").readLines()
        val start = lines.indexOfFirst { it.contains("override val id = \"$id\"") }
        assertTrue("step $id not found", start >= 0)
        val end = (start + 1 until lines.size).first { lines[it] == "    }" }
        return lines.subList(start, end)
    }

    private fun yieldsBefore(b: List<String>, k: Int) = b.subList(maxOf(0, k - 4), k + 1).joinToString("\n").contains("yieldPoint(")

    @Test fun theWorldMapYieldsBeforeEachPicture() {
        val b = body("worldmap")
        var reads = 0
        for ((k, l) in b.withIndex()) {
            if (!l.contains("stitch(g,") && !l.contains("g.blp(")) continue
            reads++
            assertTrue("worldmap: a picture read without a yield point: ${l.trim()}", yieldsBefore(b, k))
        }
        assertEquals("the base, each overlay and the highlight", 3, reads)
    }

    @Test fun homesMinimapYieldsBeforeEachTile() {
        val b = body("minimap")
        val reads = b.withIndex().filter { (_, l) -> l.contains("g.blp(") }
        assertEquals(1, reads.size)
        for ((k, l) in reads) assertTrue("minimap: a tile read without a yield point: ${l.trim()}", yieldsBefore(b, k))
    }
}
