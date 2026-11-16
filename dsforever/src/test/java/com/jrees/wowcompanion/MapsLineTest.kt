package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE START SCREEN'S MAPS LINE (the user's pick, 2026-11-16, #284: "game art shows its finished but map is still empty" - the player
 * played under "Game art: ready", which is only the art Play needs): "Maps: build while the game is closed" while the world map or
 * Home's map is still to build, "Maps: ready" once both are stamped; Spanish and French. The rows (SetupFlow), the stamp's answer
 * (ArtHarvest.mapsReady) and the launcher's wiring (WoWForeverScreen, read as source). The drawn box: app MapsLineShotsTest.
 */
class MapsLineTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val i18n = File(src, "../../../../assets/companion/i18n")

    private val ok = SetupFlow.Inputs(path = "/storage/emulated/0/WoW Forever", folderFound = true, loginSaved = true,
        art = SetupFlow.Art.READY, addonsInstalled = true, client = "1.60.1.70685 (up to date)")

    @Test fun theWordsAreThePick() {
        assertEquals("Maps: build while the game is closed", SetupFlow.MAPS_WAIT)
        assertEquals("Maps: ready", SetupFlow.MAPS_READY)
    }

    @Test fun itsRowSitsUnderTheGameArtWithAllItsWords() {
        val waiting = SetupFlow.checks(ok.copy(maps = false))
        val art = waiting.indexOfFirst { it.label.startsWith("Game art:") }
        assertEquals(SetupFlow.Check(SetupFlow.MAPS_WAIT, SetupFlow.Mark.OPTIONAL, full = true), waiting[art + 1])
        val ready = SetupFlow.checks(ok.copy(maps = true))
        assertEquals(SetupFlow.Check(SetupFlow.MAPS_READY, SetupFlow.Mark.OK, full = true), ready[art + 1])
        // the top box keeps its words; every other OK / waiting row is still its name only
        val box = SetupFlow.terse(waiting)
        assertEquals(SetupFlow.MAPS_WAIT, box[art + 1].label)
        assertEquals("Game art", box[art].label)
        assertEquals(SetupFlow.MAPS_READY, SetupFlow.terse(ready)[art + 1].label)
        assertEquals(listOf("Game folder", "Battle.net login", "Game art", SetupFlow.MAPS_READY, "Add-ons", "Client build"),
            SetupFlow.terse(ready).map { it.label })
        // no bar of its own
        assertNull(SetupFlow.bar(waiting[art + 1], ok.copy(maps = false)))
    }

    @Test fun noRowWhileTheBuildIsNotKnown() {
        val rows = SetupFlow.checks(ok.copy(maps = null))
        assertTrue(rows.none { it.label.startsWith("Maps") })
        assertEquals(SetupFlow.checks(ok).size, rows.size)
    }

    @Test fun itNeverHoldsPlayOrTheChooser() {
        for (m in listOf(null, false, true)) {
            assertTrue("$m", SetupFlow.checksPass(ok.copy(maps = m)))
            assertTrue("$m", SetupFlow.canPlay(ok.copy(maps = m)))
        }
    }

    @Test fun theStampsAnswer() {
        assertNull("no game build found", ArtHarvest.mapsReady(null, emptyList()))
        assertEquals(false, ArtHarvest.mapsReady("1.60.1.70685", listOf("worldmap", "minimap", "allicons")))
        assertEquals("the world map done, Home's map not yet", false, ArtHarvest.mapsReady("b", listOf("minimap", "terrain")))
        assertEquals("Home's map done, the world map not yet", false, ArtHarvest.mapsReady("b", listOf("worldmap")))
        assertEquals("the other background steps do not count", true, ArtHarvest.mapsReady("b", listOf("allicons", "terrain", "safepathzones")))
        assertEquals(true, ArtHarvest.mapsReady("b", emptyList()))
        assertTrue(ArtHarvest.mapStep("worldmap")); assertTrue(ArtHarvest.mapStep("minimap"))
        assertFalse(ArtHarvest.mapStep("allicons")); assertFalse(ArtHarvest.mapStep("icons"))
        val a = File(src, "ArtHarvest.kt").readText()
        assertTrue(a.contains("mapsReady(GameFiles.activeBuild(GameFiles.gameRoot(ctx))?.second, pending(ctx, beforePlay = false).map { it.id })"))
    }

    @Test fun spanishAndFrenchWithCrlf() {
        val want = mapOf("es" to listOf("Mapas: se crean con el juego cerrado", "Mapas: listos"),
            "fr" to listOf("Cartes : se créent quand le jeu est fermé", "Cartes : prêtes"))
        for ((lang, words) in want) {
            val text = File(i18n, "$lang.json").readText()
            val plain = org.json.JSONObject(text).getJSONObject("plain")
            for ((en, tr) in listOf(SetupFlow.MAPS_WAIT, SetupFlow.MAPS_READY).zip(words)) {
                assertEquals("$lang: $en", tr, plain.getString(en))
                assertTrue("$lang keeps CRLF", text.contains("\"$en\": \"$tr\",\r\n"))
                Tr.load(lang, text)
                assertEquals(tr, Tr.t(en, lang))
            }
        }
        assertEquals(SetupFlow.MAPS_WAIT, Tr.t(SetupFlow.MAPS_WAIT, "en"))
        // drawn through Tr, row by row (LoadingTopView)
        assertTrue(File(src, "LoadingTopView.kt").readText().contains("for (l in lines) { c.drawText(Tr.t(l), x + 64f * k, y, p)"))
    }

    @Test fun theReportSaysIt() {
        assertTrue(File(src, "StartupDiag.kt").readText()
            .contains("maps: \${when (i.maps) { true -> \"ready\"; false -> \"still to build\"; null -> \"?\" }}"))
    }

    @Test fun theLauncherReadsTheStampOffTheMainThreadAndWhenAMapStepIsDone() {
        val f = listOf("../app/src/main/java/app/gamenative/ui/screen/wow/WoWForeverScreen.kt", "app/src/main/java/app/gamenative/ui/screen/wow/WoWForeverScreen.kt")
            .map { File(it) }.first { it.isFile }
        val s = f.readText()
        assertTrue(s.contains("maps = mapsReady,"))
        val refresh = s.substring(s.indexOf("fun refreshMaps() {"), s.indexOf("fun checkFiles(): Boolean {"))
        assertTrue(refresh.contains("scope.launch(Dispatchers.IO) {"))
        assertTrue(refresh.contains("com.jrees.wowcompanion.ArtHarvest.mapsReady(context)"))
        assertTrue(refresh.contains("withContext(Dispatchers.Main) { mapsReady = r }"))
        val check = s.substring(s.indexOf("fun checkFiles(): Boolean {"), s.indexOf("fun denyStorageAccess()"))
        assertTrue("with the art's own check", check.contains("refreshMaps()"))
        assertTrue(s.contains("val mapsListener: (String) -> Unit = { id -> if (com.jrees.wowcompanion.ArtHarvest.mapStep(id)) refreshMaps() }"))
        assertTrue(s.contains("com.jrees.wowcompanion.ArtHarvest.onStepDone(mapsListener)"))
        assertTrue(s.contains("com.jrees.wowcompanion.ArtHarvest.offStepDone(mapsListener)"))
        assertTrue("a new build's art looks again", s.contains("if (state.beforePlay && artReady) refreshMaps()"))
    }
}
