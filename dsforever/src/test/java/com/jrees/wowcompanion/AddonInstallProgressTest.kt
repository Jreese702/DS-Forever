package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * issue #412 ("installing spoken addon", sent 7 minutes into Spoken Quests' 1.5 GB unpack, which finished seconds later): the install
 * line says how far the unpack is, and the start-up trace keeps it as one stage.
 */
class AddonInstallProgressTest {
    private fun zip(entries: Int, size: Int): File {
        val f = File(Files.createTempDirectory("addonzip").toFile(), "pack.zip")
        val rnd = java.util.Random(7)
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("Pack/Pack.toc")); z.write("## Version: 1.0\n".toByteArray()); z.closeEntry()
            for (i in 0 until entries) {
                z.putNextEntry(ZipEntry("Pack/audio/line$i.ogg"))
                z.write(ByteArray(size).also { rnd.nextBytes(it) })                   // incompressible, as voice files are
                z.closeEntry()
            }
        }
        return f
    }

    @Test fun theUnpackSaysHowFarItIs() {
        val z = zip(entries = 60, size = 20_000)
        val to = Files.createTempDirectory("staging").toFile()
        val seen = ArrayList<Int>()
        DependencyAddons.unpack(z, to, "", progress = { seen.add(it) })
        assertTrue(seen.toString(), seen.size > 20)                                   // many steps, not one jump
        assertEquals(seen.sorted(), seen)                                             // never back
        assertEquals(seen.distinct(), seen)                                           // each value once
        assertEquals(100, seen.last())
        assertTrue(seen.first() <= 5)
        assertEquals(61, to.walkTopDown().count { it.isFile })                       // the unpack itself as before
    }

    @Test fun noProgressAskedIsTheUnpackAsBefore() {
        val z = zip(entries = 3, size = 100)
        val to = Files.createTempDirectory("staging2").toFile()
        DependencyAddons.unpack(z, to, "")
        assertTrue(File(to, "Pack/Pack.toc").isFile)
    }

    @Test fun theLine() {
        assertEquals("Installing add-ons: Spoken Quests...", ThirdPartyAddons.installLine("Spoken Quests", 0))
        assertEquals("Installing add-ons: Spoken Quests 42%...", ThirdPartyAddons.installLine("Spoken Quests", 42))
        assertEquals("Installing add-ons: Spoken Quests...", ThirdPartyAddons.installLine("Spoken Quests", 100))
    }

    @Test fun oneStageInTheStartUpTrace() {
        fun stage(busy: String) = StartupTrace.stageOf(SetupFlow.Inputs(path = "/g", folderFound = true, busy = busy), gameUp = false).first
        assertEquals("busy: Installing add-ons: Spoken Quests...", stage("Installing add-ons: Spoken Quests 42%..."))
        assertEquals(stage("Installing add-ons: Spoken Quests..."), stage("Installing add-ons: Spoken Quests 7%..."))
        assertEquals("busy: Checking add-ons...", stage("Checking add-ons..."))
        // StartupTrace.mark keeps a stage once while its name stays the same: one name for the whole unpack
        assertEquals(1, listOf(0, 1, 2, 50, 99, 100).map { stage(ThirdPartyAddons.installLine("Spoken Quests", it)) }.distinct().size)
    }
}
