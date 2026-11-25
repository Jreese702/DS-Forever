package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1529 RENDER SIZE (the user's pick 2026-11-24, "Yes, add the setting (Recommended)" = "Default stays full size; players on a base Thor
 * can pick 900p or 720p for more FPS"), off-device: the choices per profile, the size a launch uses, the band's line, the reset, the
 * launch's wiring (the container's screen AND gxWindowedResolution), and that the tap pad's mapping takes every size on the top screen.
 */
class RenderSizeTest {
    @Test fun theThorsTopScreenOffersFullSize900pAnd720p() {
        assertEquals(listOf("Full size", "900p", "720p"), RenderSize.choices("1920x1080").map { it.label })
        assertEquals(listOf(null, "1600x900", "1280x720"), RenderSize.choices("1920x1080").map { it.size })
    }

    @Test fun profilesAlreadyAt720pOrNotSixteenByNineOfferNothingSmaller() {
        assertEquals(listOf("Full size"), RenderSize.choices("1280x720").map { it.label })      // the Lite, the Adreno 8xx profile
        assertEquals(listOf("Full size"), RenderSize.choices("1280x960").map { it.label })      // a 4:3 panel (PanelFit)
        assertEquals(listOf("Full size"), RenderSize.choices(null).map { it.label })            // the app (no launcher)
        assertEquals(listOf("Full size"), RenderSize.choices("junk").map { it.label })
        assertEquals(listOf("Full size", "1080p", "900p", "720p"), RenderSize.choices("2560x1440").map { it.label })
    }

    @Test fun theLaunchUsesThePickOnlyWhereItFits() {
        assertEquals("1920x1080", RenderSize.effective("1920x1080", "", swapped = false))            // the default: Full size, as before
        assertEquals("1280x720", RenderSize.effective("1920x1080", "1280x720", swapped = false))
        assertEquals("1600x900", RenderSize.effective("1920x1080", "1600x900", swapped = false))
        assertEquals("1920x1080", RenderSize.effective("1920x1080", "1280x720", swapped = true))      // Swap screens: Full size
        assertEquals("1280x720", RenderSize.effective("1280x720", "1600x900", swapped = false))      // not a choice there: the profile's
        assertEquals("1920x1080", RenderSize.effective("1920x1080", "800x600", swapped = false))
        assertEquals("render size 1280x720 (full 1920x1080)", RenderSize.line("1920x1080", "1280x720"))
        assertEquals("render size full (1920x1080)", RenderSize.line("1920x1080", "1920x1080"))
    }

    @Test fun theFrameLimitBandCarriesTheLineWhereThereIsAChoice() {
        val M = PerformanceTileModel
        val thor = PerformanceTileModel.State(renderSizes = listOf("Full size", "900p", "720p"), render = 2)
        val l = M.lines(M.LIMIT, thor)
        assertEquals(listOf(M.L_LIMIT, M.L_RENDER), l.map { it.key })
        assertEquals("Render size", l[1].label)
        assertEquals(listOf("Full size", "900p", "720p"), l[1].words)
        assertEquals(2, l[1].chosen)
        assertEquals(listOf(M.L_LIMIT), M.lines(M.LIMIT, PerformanceTileModel.State(renderSizes = listOf("Full size"))).map { it.key })
        assertEquals("Render size: from the next game start", M.status(M.LIMIT, thor.copy(renderChanged = true)))
        assertEquals(0, RenderSize.index("1920x1080", ""))
        assertEquals(1, RenderSize.index("1920x1080", "1600x900"))
        assertEquals(0, RenderSize.index("1280x720", "1600x900"))
        assertTrue(ResetPlan.PREFS.any { it.file == "settings" && it.key == "renderSize" && it.kind == ResetPlan.Kind.SETTINGS })
    }

    @Test fun theTapPadMapsEverySizeOntoTheTopScreen() {
        // TapBridge maps the game's own pixels (the add-on's pw / ph: the container's screen) onto the 1920x1080 top screen by its shape
        for (s in RenderSize.choices("1920x1080")) {
            val (w, h) = RenderSize.parse(s.size ?: "1920x1080")!!
            assertTrue(s.label, TapShape.sameShape(w.toDouble(), h.toDouble(), 1920, 1080))
            // a cell's centre, the game's corner pixels: on the top screen at the same fraction
            val x = w * 0.8125; val y = h * 0.2
            assertEquals(1560.0, x * 1920 / w, 1e-9); assertEquals(216.0, y * 1080 / h, 1e-9)
        }
        assertFalse(TapShape.sameShape(1542.0, 1080.0, 1920, 1080))        // #305's mismatch is still refused
        assertNull(RenderSize.parse("1920 x 1080"))
    }

    private fun src(vararg p: String) = p.map { File(it) }.first { it.isFile }.readText()

    @Test fun theLaunchPutsTheSizeInTheContainerAndInConfigWtf() {
        val w = src("../app/src/main/java/app/gamenative/ui/screen/wow/WoWForeverScreen.kt", "app/src/main/java/app/gamenative/ui/screen/wow/WoWForeverScreen.kt")
        assertTrue(w.contains("val gpu = if (render == renderFull) driverGpu else driverGpu.copy(screenSize = render)"))
        assertTrue(w.contains("val screen = if (render != renderFull) render else gameScreenSize(context)"))
        assertTrue(w.contains("put(\"screenSize\", gpu.screenSize)"))
        assertTrue(w.contains("com.jrees.wowcompanion.LauncherHooks.renderFull = gpu.screenSize"))
        // #489: RenderScale is still never forced (only a default where missing)
        val g = src("../companion/app/src/main/java/com/jrees/wowcompanion/GameConfigWtf.kt", "companion/app/src/main/java/com/jrees/wowcompanion/GameConfigWtf.kt")
        val forced = g.substring(g.indexOf("private val FORCED_CONFIG_KEYS"), g.indexOf("\"gxMaximize\", \"gxWindowedResolution\")"))
        assertFalse(forced.contains("RenderScale"))
    }

    @Test fun gxWindowedResolutionIsTheChosenSize() {
        val root = kotlin.io.path.createTempDirectory("rs").toFile()
        try {
            val p = WowProduct.values().first { it.portal == null }
            GameConfigWtf.ensure(root, p, null, screen = "1280x720")
            val cfg = File(root, "${p.flavorDir}/WTF/Config.wtf").readText()
            assertTrue(cfg, cfg.contains("SET gxWindowedResolution \"1280x720\""))
            assertTrue(cfg.contains("SET gxMaximize \"1\""))
            // back to Full size: the next start writes the screen's own size again (forced at every start, #305)
            GameConfigWtf.ensure(root, p, null, screen = "1920x1080")
            assertTrue(File(root, "${p.flavorDir}/WTF/Config.wtf").readText().contains("SET gxWindowedResolution \"1920x1080\""))
        } finally { root.deleteRecursively() }
    }
}
