package app.gamenative.ui.screen.wow

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The user, 2026-11-17: a region switched by mistake downloaded another build with no way out. The region card says first whether a
 * switch downloads ([WowClientDownloader.regionNeedsDownload]), and a stopped update leaves the old client whole
 * ([WowClientDownloader.stage] / [WowClientDownloader.commit]: the files staged beside the old ones, moved over only at the end).
 */
class WowRegionSwitchTest {
    private val root: File = Files.createTempDirectory("dsf-region-switch").toFile()

    @After fun tearDown() { root.deleteRecursively() }

    private val local = mapOf("Branch" to "us", "Version" to "1.60.1.70888", "Build Key" to "usbuild")
    private fun row(region: String, build: String, version: String) =
        mapOf("Region" to region, "BuildConfig" to build, "VersionsName" to version)

    @Test fun theSameBuildUsesThisInstall() {
        val rows = listOf(row("us", "usbuild", "1.60.1.70888"), row("eu", "usbuild", "1.60.1.70888"))
        assertEquals(false, WowClientDownloader.regionNeedsDownload(local, rows, "eu"))
        assertEquals(false, WowClientDownloader.regionNeedsDownload(local, rows, "us"))
    }

    @Test fun anotherBuildDownloads() {
        val rows = listOf(row("us", "usbuild", "1.60.1.70888"), row("eu", "eubuild", "1.60.1.70862"))
        assertEquals(true, WowClientDownloader.regionNeedsDownload(local, rows, "eu"))
        // the same version name with another BuildConfig is another build too (regions fix)
        assertEquals(true, WowClientDownloader.regionNeedsDownload(local, listOf(row("kr", "krbuild", "1.60.1.70888")), "kr"))
    }

    @Test fun noRowIsNotKnown() {
        assertNull(WowClientDownloader.regionNeedsDownload(local, emptyList(), "eu"))
        assertNull(WowClientDownloader.regionNeedsDownload(local, listOf(mapOf("Region" to "eu")), "eu"))
    }

    @Test fun theCheckAndTheCardAgree() {
        assertFalse(WowClientDownloader.needsDownload("1.60.1.70888", "usbuild", "1.60.1.70888", "usbuild"))
        assertFalse(WowClientDownloader.needsDownload("1.60.1.70888", "usbuild", "1.60.1.70888", ""))
        assertTrue(WowClientDownloader.needsDownload("1.60.1.70888", "usbuild", "1.60.1.70862", "usbuild"))
    }

    private fun files(): List<Pair<File, File>> = listOf("a.dll", "b.dll", "Wow.exe").map { n ->
        val d = File(root, n).apply { writeText("old $n") }
        File(root, "$n.download") to d
    }

    @Test fun aStopLeavesTheOldClientWholeAndNoTemps() {
        val f = files()
        var written = 0
        try {
            WowClientDownloader.stage(f, { it.first }, { it.second }, stopped = { written == 2 }) { (t, _), out ->
                assertEquals(t, out); out.writeText("new"); written++
            }
            fail("not stopped")
        } catch (e: WowClientDownloader.UpdateStopped) {
            // expected
        }
        assertEquals(2, written)
        for ((t, d) in f) {
            assertFalse("$t left", t.exists())
            assertEquals("old ${d.name}", d.readText())
        }
    }

    @Test fun aFailureKeepsTheWholeTempsForTheNextTry() {
        val f = files()
        try {
            WowClientDownloader.stage(f, { it.first }, { it.second }, stopped = { false }) { (_, d), out ->
                if (d.name == "Wow.exe") throw java.io.IOException("lost link")
                out.writeText("new")
            }
            fail("no failure")
        } catch (e: java.io.IOException) {
            assertFalse(e is WowClientDownloader.UpdateStopped)
        }
        assertTrue(f[0].first.isFile)
        for ((_, d) in f) assertEquals("old ${d.name}", d.readText())
    }

    @Test fun theOldFilesAreReplacedOnlyAtTheCommitInOrder() {
        val f = files()
        val moves = WowClientDownloader.stage(f, { it.first }, { it.second }, stopped = { false }) { _, out -> out.writeText("new") }
        for ((_, d) in f) assertEquals("old ${d.name}", d.readText())
        assertEquals(f.map { it.second }, moves.map { it.second })      // the exe last
        WowClientDownloader.commit(moves)
        for ((t, d) in f) { assertEquals("new", d.readText()); assertFalse(t.exists()) }
    }
}
