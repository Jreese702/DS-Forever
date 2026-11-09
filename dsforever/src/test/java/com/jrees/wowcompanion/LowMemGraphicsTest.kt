package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Low-memory graphics (the user's pick 2026-11-08, "Automatic, with a toggle"): the smaller textures in Config.wtf, and back when off */
class LowMemGraphicsTest {
    private fun conf(root: File) = File(root, "${WowProduct.LAUNCH.flavorDir}/WTF/Config.wtf").readLines()
        .filter { it.startsWith("SET ") }.associate { l -> l.removePrefix("SET ").split(" ", limit = 2).let { it[0] to it[1] } }

    @Test fun onWritesTheSmallerTexturesOffPutsTheDefaultsBack() {
        val root = Files.createTempDirectory("lowmem").toFile()
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, mapOf("Branch" to "us"))                     // a new file: the defaults
        assertEquals("\"0\"", conf(root)["worldBaseMip"])
        assertEquals("\"2\"", conf(root)["graphicsTextureResolution"])
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, mapOf("Branch" to "us"), lowMemory = true)
        val low = conf(root)
        for ((k, v) in GameConfigWtf.LOW_MEMORY_GRAPHICS) assertEquals(k, v, low[k])
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, mapOf("Branch" to "us"), lowMemory = false)
        assertEquals("\"0\"", conf(root)["worldBaseMip"])
        assertEquals("\"2\"", conf(root)["graphicsTextureResolution"])
        assertEquals("\"0\"", conf(root)["componentTextureLevel"])
    }

    @Test fun aNewFileOnASmallThorStartsSmall() {
        val root = Files.createTempDirectory("lowmem2").toFile()
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, mapOf("Branch" to "us"), lowMemory = true)
        val c = conf(root)
        assertEquals("\"2\"", c["worldBaseMip"])
        assertEquals("\"0\"", c["graphicsTextureResolution"])
        assertTrue(c.containsKey("portal"))
    }
}
