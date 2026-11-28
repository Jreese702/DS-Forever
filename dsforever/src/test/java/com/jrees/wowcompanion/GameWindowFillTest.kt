package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * #305 (1.0.32: "controls not working on bottom screen"; its pad: the game's screen 1542x1080 on the 1920x1080 top screen, every tap
 * refused): the user's pick 2026-11-16, "force the game to fill the top screen at EVERY start" - gxMaximize 1 and gxWindowedResolution
 * the size of the screen the game shows on (the Thor's top screen; the game's own with Swap screens / another device), forced.
 */
class GameWindowFillTest {
    private val row = mapOf("Branch" to "us")
    private fun wtfDir(root: File) = File(root, "${WowProduct.LAUNCH.flavorDir}/WTF")
    private fun conf(root: File) = File(wtfDir(root), "Config.wtf").readLines()
        .filter { it.startsWith("SET ") }.associate { l -> l.removePrefix("SET ").split(" ", limit = 2).let { it[0] to it[1] } }
    private fun folder(vararg lines: String): File = Files.createTempDirectory("cfgonce").toFile().also { root ->
        File(wtfDir(root), "Config.wtf").apply { parentFile.mkdirs() }.writeText(lines.joinToString("\n") + "\n")
    }
    /** the player's own pick in the game's options (the game rewrites the line) */
    private fun pick(root: File, key: String, value: String) {
        val f = File(wtfDir(root), "Config.wtf")
        f.writeText(f.readLines().map { if (it.startsWith("SET $key ")) "SET $key \"$value\"" else it }.joinToString("\n") + "\n")
    }

    @Test fun theWindowFillsTheScreenAtEveryStart() {
        val root = folder("SET gxMaximize \"0\"", "SET gxWindowedResolution \"1542x1080\"", "SET farclip \"1200\"")
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, row, screen = "1920x1080")
        assertEquals("\"1\"", conf(root)["gxMaximize"])
        assertEquals("\"1920x1080\"", conf(root)["gxWindowedResolution"])
        // the game's own Windowed pick again: put back at the next start, every start
        pick(root, "gxMaximize", "0"); pick(root, "gxWindowedResolution", "1542x1080")
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, row, screen = "1920x1080")
        assertEquals("\"1\"", conf(root)["gxMaximize"])
        assertEquals("\"1920x1080\"", conf(root)["gxWindowedResolution"])
        // Swap screens / another device: the size of the screen the game shows on
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, row, screen = "1240x1080")
        assertEquals("\"1240x1080\"", conf(root)["gxWindowedResolution"])
        // no size read (or not a size): 1920x1080
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, row, screen = null)
        assertEquals("\"1920x1080\"", conf(root)["gxWindowedResolution"])
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, row, screen = "1920x1080\"\nSET portal \"test")
        assertEquals("\"1920x1080\"", conf(root)["gxWindowedResolution"])
        assertEquals("\"us\"", conf(root)["portal"])
        assertEquals(1, File(wtfDir(root), "Config.wtf").readLines().count { it.startsWith("SET gxWindowedResolution ") })
    }

    /**
     * Based on a pull request by RenaeTheRaven (PR 615): ResampleQuality is no longer forced at every start (the #489 ruling: "only when
     * the player hasn't chosen, never overwrite the player's own value"), so the game's own FSR 1.0 (3) can upscale a RenderScale below
     * 1. Still a default: a new file and a file without the line get 0.
     */
    @Test fun thePlayersOwnResampleQualityIsKept() {
        val root = folder("SET ResampleQuality \"3\"", "SET RenderScale \"0.75\"")
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, row, screen = "1920x1080")
        assertEquals("\"3\"", conf(root)["ResampleQuality"])
        assertEquals("\"0.75\"", conf(root)["RenderScale"])
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, row, screen = "1920x1080")
        assertEquals("\"3\"", conf(root)["ResampleQuality"])
        val bare = folder("SET farclip \"1200\"")
        GameConfigWtf.ensure(bare, WowProduct.LAUNCH, row, screen = "1920x1080")
        assertEquals("\"0\"", conf(bare)["ResampleQuality"])
        val fresh = Files.createTempDirectory("cfgrq").toFile()
        GameConfigWtf.ensure(fresh, WowProduct.LAUNCH, row, screen = "1920x1080")
        assertEquals("\"0\"", conf(fresh)["ResampleQuality"])
    }

    @Test fun aNewFileTakesTheScreensSize() {
        val root = Files.createTempDirectory("cfgnew").toFile()
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, row, screen = "2400x1080")
        assertEquals("\"1\"", conf(root)["gxMaximize"])
        assertEquals("\"2400x1080\"", conf(root)["gxWindowedResolution"])
    }

    @Test fun theScreenSizeIsLandscape() {
        assertEquals("1920x1080", GameConfigWtf.screenSize(1080, 1920))     // the Thor's display 0 mode: 1080x1920
        assertEquals("1240x1080", GameConfigWtf.screenSize(1240, 1080))
        assertNull(GameConfigWtf.screenSize(0, 1080))
        assertNull(GameConfigWtf.screenSize(1920, -1))
    }
}
