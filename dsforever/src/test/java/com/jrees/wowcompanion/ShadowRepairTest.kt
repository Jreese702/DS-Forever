package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * test57 (the launch plan's hazard, live): the shadow's beta folder was a LINK into the shared game folder (test55's launch run linked
 * the shared _classic_beta_ in as an ordinary entry), the repair walked it and "backed up" then deleted the real game - WowB-ARM64.exe,
 * WTF, Interface. Rebuilt here with a Windows junction (no privilege needed; the device's symlink resolves the same way).
 */
class ShadowRepairTest {
    private val flavors = WowProduct.values().map { it.flavorDir }.toSet()
    private val tmp = Files.createTempDirectory("shadow").toFile()
    private val game = File(tmp, "WoW Forever")               // the shared game folder
    private val shadow = File(tmp, "gameroot")                // the app's private shadow
    private val backup = File(tmp, "backup")

    private fun gameFiles(): Map<String, String> {
        File(game, ".build.info").apply { parentFile.mkdirs() }.writeText("rows")
        File(game, "Data/data.000").apply { parentFile.mkdirs() }.writeText("data")
        File(game, "_classic_beta_/WowB-ARM64.exe").apply { parentFile.mkdirs() }.writeText("beta exe")
        File(game, "_classic_beta_/WTF/Account/12345#1/SavedVariables/WowCompanion.lua").apply { parentFile.mkdirs() }.writeText("sv")
        File(game, "_classic_beta_/Interface/AddOns/WowCompanion/WowCompanion.toc").apply { parentFile.mkdirs() }.writeText("toc")
        File(game, "_forever_/Wow-arm64.exe").apply { parentFile.mkdirs() }.writeText("wowf exe")
        return snapshot()
    }
    private fun snapshot(): Map<String, String> = game.walkTopDown().filter { it.isFile }.associate { it.relativeTo(game).path.replace('\\', '/') to it.readText() }

    private fun junction(at: File, target: File): Boolean {
        if (!System.getProperty("os.name").lowercase().contains("win")) return false
        at.parentFile.mkdirs()
        val p = ProcessBuilder("cmd", "/c", "mklink", "/J", at.absolutePath, target.absolutePath).redirectErrorStream(true).start()
        p.inputStream.readBytes(); return p.waitFor() == 0 && at.exists()
    }
    /** what test55 left on the Thor: the shadow root's _classic_beta_ resolving to the shared one */
    private fun test55Shadow(): File {
        val link = File(shadow, "_classic_beta_")
        assumeTrue("a directory junction (Windows)", junction(link, File(game, "_classic_beta_")))
        File(shadow, "_forever_").mkdirs()                      // the launch run's own private folder
        return link
    }

    @Test fun theShadowsBetaFolderThatIsTheSharedOneIsRefusedAndNothingMoves() {
        val before = gameFiles()
        test55Shadow()
        // the beta chosen again (test57): its shadow folder resolves into the shared game folder - refused, nothing built
        val e = runCatching { ShadowRepair.flavorFolder(shadow, "_classic_beta_", game, flavors) { Files.isSymbolicLink(it.toPath()) } }.exceptionOrNull()
        assertTrue("refused: ${e?.message}", e != null && e.message!!.contains("shared game folder"))
        assertEquals(before, snapshot())
        assertFalse(backup.exists())
    }

    @Test fun theRepairNeverMovesAFileOfTheSharedFolderSeenThroughALink() {
        val before = gameFiles()
        val link = test55Shadow()
        // the old walk: every entry of the linked folder handed to the repair (test57's "0 files merged, N older kept in the backup")
        for (e in link.listFiles()!!) {
            val r = ShadowRepair.repair(e, File(game, "_classic_beta_/${e.name}"), backup, { false }, game)
            assertTrue(r.line(), r.state.startsWith("REFUSED"))
        }
        // and without the shared root given (an old caller): the same file by two names is never copied onto itself nor deleted
        for (e in link.listFiles()!!) ShadowRepair.repair(e, File(game, "_classic_beta_/${e.name}"), backup, { false })
        assertEquals(before, snapshot())
    }

    @Test fun aLinkedFlavorFolderIsUnlinkedAndAPrivateOneMade() {
        val before = gameFiles()
        val link = test55Shadow()
        // the device's case: the entry IS a symbolic link - unlinked (the link only), a private folder made in its place
        val fl = ShadowRepair.flavorFolder(shadow, "_classic_beta_", game, flavors) { it.absolutePath == link.absolutePath && it.exists() }
        assertEquals(File(shadow, "_classic_beta_").absolutePath, fl.absolutePath)
        assertFalse(ShadowRepair.inside(fl, game))
        assertEquals(before, snapshot())
    }

    @Test fun theShadowRootNeverLinksAClientsFlavorFolder() {
        gameFiles()
        val names = ShadowRepair.rootLinks(game, flavors).map { it.name }.toSet()
        assertEquals(setOf(".build.info", "Data"), names)
    }

    @Test fun aRealPrivateEntryIsStillMergedIntoTheSharedFolder() {
        gameFiles()
        // the client's own private write (test22's case): merged into the shared folder, the shadow's copy gone
        val fl = ShadowRepair.flavorFolder(shadow, "_classic_beta_", game, flavors) { Files.isSymbolicLink(it.toPath()) }
        File(fl, "Screenshots/shot.jpg").apply { parentFile.mkdirs() }.writeText("jpg")
        val r = ShadowRepair.repair(File(fl, "Screenshots"), File(game, "_classic_beta_/Screenshots"), backup, { false }, game)
        assertEquals("real dir", r.state); assertEquals(1, r.merged)
        assertEquals("jpg", File(game, "_classic_beta_/Screenshots/shot.jpg").readText())
        assertFalse(File(fl, "Screenshots").exists())
        assertEquals("beta exe", File(game, "_classic_beta_/WowB-ARM64.exe").readText())
    }
}
