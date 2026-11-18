package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * #293 (1.0.32: settings that do not stay): the user's pick 2026-11-16, GameConfigWtf's legacy values' rewrite "runs ONCE per game
 * folder (a marker), never again, so a player's own in-game pick sticks"; Low-memory graphics' Off still puts the defaults back (once).
 */
class GameConfigOnceTest {
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

    @Test fun theOldValuesArePutRightOnceThenThePlayersPickStays() {
        val root = folder("SET farclip \"3000\"", "SET graphicsTextureResolution \"0\"", "SET worldBaseMip \"2\"")
        val marker = File(wtfDir(root), GameConfigWtf.LEGACY_DONE)
        assertFalse(marker.exists())
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, row)                   // the first start with this app: the rewrite runs
        assertEquals("\"1200\"", conf(root)["farclip"])
        assertEquals("\"2\"", conf(root)["graphicsTextureResolution"])
        assertEquals("\"0\"", conf(root)["worldBaseMip"])
        assertTrue("the folder's marker", marker.isFile)
        // the player picks Texture Resolution Low and a far view in the game's options: they stay, start after start
        pick(root, "graphicsTextureResolution", "0"); pick(root, "farclip", "3000")
        repeat(2) { GameConfigWtf.ensure(root, WowProduct.LAUNCH, row) }
        assertEquals("\"0\"", conf(root)["graphicsTextureResolution"])
        assertEquals("\"3000\"", conf(root)["farclip"])
    }

    @Test fun aNewFileNeverHasItsPicksTurnedBack() {
        val root = Files.createTempDirectory("cfgfresh").toFile()
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, row)
        assertTrue(File(wtfDir(root), GameConfigWtf.LEGACY_DONE).isFile)
        pick(root, "graphicsTextureResolution", "0")
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, row)
        assertEquals("\"0\"", conf(root)["graphicsTextureResolution"])
    }

    @Test fun lowMemoryOffPutsTheDefaultsBackOnceOnly() {
        val root = Files.createTempDirectory("cfglow").toFile()
        val lowOn = File(wtfDir(root), GameConfigWtf.LOW_MEMORY_ON)
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, row)
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, row, lowMemory = true)
        assertTrue("on: the low-memory marker", lowOn.isFile)
        assertEquals("\"0\"", conf(root)["graphicsTextureResolution"])
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, row, lowMemory = false)
        assertEquals("off: the defaults back", "\"2\"", conf(root)["graphicsTextureResolution"])
        assertEquals("\"0\"", conf(root)["worldBaseMip"])
        assertFalse("off: the marker gone", lowOn.exists())
        // the player's own Low after that stays
        pick(root, "graphicsTextureResolution", "0")
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, row, lowMemory = false)
        assertEquals("\"0\"", conf(root)["graphicsTextureResolution"])
        // on again: forced as before (by design while the setting is on)
        pick(root, "worldBaseMip", "0")
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, row, lowMemory = true)
        assertEquals("\"2\"", conf(root)["worldBaseMip"])
    }

    @Test fun anOlderAppsLowMemoryFolderIsPutRightAtTheFirstStart() {
        // a folder an older app left with the smaller textures (no markers at all), the setting off now: the one-time rewrite covers it
        val root = folder("SET graphicsTextureResolution \"0\"", "SET componentTextureLevel \"1\"")
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, row, lowMemory = false)
        assertEquals("\"2\"", conf(root)["graphicsTextureResolution"])
        assertEquals("\"0\"", conf(root)["componentTextureLevel"])
    }
    /**
     * #489 (1.0.33: "Resolution Scaling setting not being saved"): RenderScale was one of the keys rewritten at every start, so the
     * game's Resolution Scaling went back to 100% each time. The user, 2026-11-18: only set it once / only when the player hasn't chosen,
     * never overwrite the player's own value. A new file still gets the default; a file without the line gets it added; a value the
     * player picked stays, start after start.
     */
    @Test fun thePlayersResolutionScaleStays() {
        val fresh = Files.createTempDirectory("cfgscale").toFile()
        GameConfigWtf.ensure(fresh, WowProduct.LAUNCH, row)
        assertEquals("a new file: the default", "\"1\"", conf(fresh)["RenderScale"])
        pick(fresh, "RenderScale", "0.75")
        repeat(2) { GameConfigWtf.ensure(fresh, WowProduct.LAUNCH, row) }
        assertEquals("the player's pick stays", "\"0.75\"", conf(fresh)["RenderScale"])
        // a folder with no RenderScale line (the player never chose): the default is added once
        val none = folder("SET gxApi \"D3D11\"")
        GameConfigWtf.ensure(none, WowProduct.LAUNCH, row)
        assertEquals("missing: added", "\"1\"", conf(none)["RenderScale"])
        // the keys #305 forces are still forced
        val win = folder("SET gxMaximize \"0\"", "SET RenderScale \"0.5\"")
        GameConfigWtf.ensure(win, WowProduct.LAUNCH, row)
        assertEquals("\"1\"", conf(win)["gxMaximize"])
        assertEquals("\"0.5\"", conf(win)["RenderScale"])
    }
}
