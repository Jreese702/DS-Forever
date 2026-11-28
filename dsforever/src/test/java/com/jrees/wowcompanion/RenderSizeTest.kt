package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RENDER SIZE (Settings > Performance, the Frame limit band; the user's rulings 2026-11-27 on PR 615, based on a pull request by
 * RenaeTheRaven): 1080p / 900p / 720p from the next game start; 720p on EVERY device until the player picks, a pick kept and always
 * winning; an existing player who never picked (no stored value) gets 720p after the update. Reset settings takes it back to 720p.
 */
class RenderSizeTest {
    private val M = PerformanceTileModel

    @After fun reset() { Settings.startForTest(null); RenderSize.launched = null }

    @Test fun theChoicesAndTheirSizes() {
        assertEquals(listOf(1080, 900, 720), RenderSize.CHOICES)
        assertEquals("1920x1080", RenderSize.size(1080))
        assertEquals("1600x900", RenderSize.size(900))
        assertEquals("1280x720", RenderSize.size(720))
        assertEquals(listOf("1080p", "900p", "720p"), RenderSize.CHOICES.map { RenderSize.label(it) })
        assertEquals(720, RenderSize.linesOf("1280x720")); assertEquals(1080, RenderSize.linesOf("1080x1920"))
        assertEquals(null, RenderSize.linesOf("bad"))
    }

    @Test fun sevenTwentyOnEveryDeviceUntilThePlayerPicks() {
        assertEquals(720, RenderSize.DEFAULT)
        assertEquals(720, RenderSize.chosen(null))
        // a value no build offers (a hand-edited or future one): the default
        assertEquals(720, RenderSize.chosen(1440)); assertEquals(720, RenderSize.chosen(0))
        // the setting reads no device at all: the same default on an 8 GB, 12 GB or 16 GB Thor and anywhere else
        val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion/RenderSize.kt", "companion/app/src/main/java/com/jrees/wowcompanion/RenderSize.kt")
            .map { java.io.File(it) }.first { it.isFile }.readText()
        assertFalse(src.contains("DeviceTier") || src.contains("totalMb") || src.contains("eightGb"))
    }

    @Test fun anExistingPlayerWhoNeverPickedGetsSevenTwentyAndAPickIsKept() {
        // an install from before this build: its settings file has other values and no render size
        val p = HomeLayoutStoreTest.Prefs(hashMapOf("lowMemGraphics" to true, "graphicsDriver" to "recommended"))
        Settings.startForTest(p)
        assertEquals(720, Settings.renderSize)
        assertFalse(Settings.renderSizePicked)
        assertFalse("reading writes nothing", p.m.containsKey(RenderSize.KEY))
        // the player's own pick wins, and is kept (a new start reads the same file)
        Settings.renderSize = 1080
        assertEquals(1080, Settings.renderSize); assertTrue(Settings.renderSizePicked)
        Settings.startForTest(p)
        assertEquals(1080, Settings.renderSize)
        Settings.renderSize = 900
        assertEquals(900, p.m[RenderSize.KEY])
        // a pick of 720 itself is kept as a pick too (a later default change would not move it)
        Settings.renderSize = 720
        assertEquals(720, p.m[RenderSize.KEY]); assertTrue(Settings.renderSizePicked)
        // not a choice: nothing written
        Settings.renderSize = 1440
        assertEquals(720, p.m[RenderSize.KEY])
    }

    @Test fun resetSettingsTakesItBackToTheDefault() {
        val k = ResetPlan.PREFS.first { it.file == "settings" && it.key == RenderSize.KEY }
        assertEquals(ResetPlan.Kind.SETTINGS, k.kind)
    }

    @Test fun theFrameLimitBandCarriesTheRenderSizeLine() {
        val s = PerformanceTileModel.State(limit = 60, renderSize = 720)
        val lines = M.lines(M.LIMIT, s)
        assertEquals(listOf(M.L_LIMIT, M.L_RENDER), lines.map { it.key })
        val r = lines[1]
        assertEquals(M.RENDER_LABEL, r.label)
        assertEquals(listOf("1080p", "900p", "720p"), r.words)
        assertEquals(2, r.chosen)
        assertEquals(PerformanceTileModel.Kind.CHOICE, r.kind)
        assertEquals(0, M.lines(M.LIMIT, s.copy(renderSize = 1080))[1].chosen)
        // a change waiting for the running game: the Restart line and the hourglass, no status line
        val waits = s.copy(renderWaits = true)
        assertEquals(listOf(M.L_LIMIT, M.L_RENDER, M.L_RESTART), M.lines(M.LIMIT, waits).map { it.key })
        assertEquals(GameUiTiles.Badge.SENT, M.badge(M.LIMIT, waits))
        assertEquals("", M.status(M.LIMIT, waits))
        // the tile's own word stays the frame limit
        assertEquals("60 fps", M.value(M.LIMIT, waits))
    }

    @Test fun aRestartOnlyWhenTheRunningGamesSizeWouldChange() {
        assertFalse(RenderSize.needsRestart(null, 720))           // no game started by this run
        assertFalse(RenderSize.needsRestart(720, 720))
        assertTrue(RenderSize.needsRestart(720, 1080))
    }

    /**
     * single screen mode's windows over the game stand in the TOP SCREEN's own px (the display's size, 1080-line boxes scaled by it) and
     * the game's UI is laid in UI units (UIParent's height does not follow the resolution: the launcher writes no UI scale, and the
     * stream's UI scale match copies the value as it is), so a smaller render size moves neither. Nothing here reads the game's pixel
     * size (the pad's pw / ph) or the X screen
     */
    @Test fun theSingleScreenWindowsFollowTheDisplayNotTheRenderSize() {
        val dirs = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
            .map { java.io.File(it) }.first { it.isDirectory }
        val fork = listOf("src/main/java/com/jrees/wowcompanion/fork", "dsforever/src/main/java/com/jrees/wowcompanion/fork")
            .map { java.io.File(it) }.first { it.isDirectory }
        val files = (dirs.listFiles { f -> f.name.startsWith("Single") && f.name.endsWith(".kt") }!!.toList() + java.io.File(fork, "TopWindow.kt"))
        assertTrue(files.size >= 10)
        for (f in files) {
            val t = f.readText()
            for (word in listOf("optDouble(\"pw\"", "optDouble(\"ph\"", "screenInfo", "RenderSize", "renderSize"))
                assertFalse("${f.name} reads $word", t.contains(word))
        }
        // the play docks' boxes on the Thor's top screen are the same whatever the game draws at
        assertEquals(SinglePlayLayout.plan(SinglePlayLayout.Arr.OUT, false).boxes(), SinglePlayLayout.plan(SinglePlayLayout.Arr.OUT, false, w = 1920, h = 1080).boxes())
    }

    @Test fun itsWordsInSpanishAndFrench() {
        for (lang in listOf("es", "fr")) {
            val f = listOf("../companion/app/src/main/assets/companion/i18n/$lang.json", "companion/app/src/main/assets/companion/i18n/$lang.json")
                .map { java.io.File(it) }.first { it.isFile }
            val plain = org.json.JSONObject(f.readText()).getJSONObject("plain")
            assertTrue("$lang: ${M.RENDER_LABEL}", plain.has(M.RENDER_LABEL))
        }
    }
}
