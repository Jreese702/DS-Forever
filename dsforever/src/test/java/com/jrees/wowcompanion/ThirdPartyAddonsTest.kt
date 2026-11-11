package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Settings > Add-ons' way in and out, off-device (1.0.9): a pinned package from a local "GitHub" (its release by tag, its asset with
 * Range), checked for its exact size and pinned sha256, kept until a start with the game closed, its folders moved in (the old copy to
 * the backup), and out again to the backup when uninstalled; nothing is ever deleted from AddOns.
 */
class ThirdPartyAddonsTest {
    private val CRLF = "\r\n"
    private lateinit var server: ServerSocket
    private lateinit var tmp: File
    private val choices = HashMap<String, Boolean>()
    private var apiCalls = 0
    private var served = 0L                                    // the bytes the asset route sent (a resume sends fewer)
    private var zipBytes = ByteArray(0)

    private fun zipOf(vararg files: Pair<String, String>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> for ((n, t) in files) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return bo.toByteArray()
    }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun addon(bytes: ByteArray, sha256: String = sha(bytes)) = ThirdPartyAddons.Addon(
        id = "t", title = "Test", what = "a test", by = "us", license = "MIT", repo = "o/r", version = "1.0",
        core = ThirdPartyAddons.Pkg("t/v1.0", "Test-1.0.zip", bytes.size.toLong(), sha256, listOf("TestA", "TestB")))

    @Before fun up() {
        tmp = Files.createTempDirectory("addons").toFile()
        zipBytes = zipOf("TestA/TestA.toc" to "## Version: 1.0\n", "TestA/a.lua" to "x=1", "TestB/TestB.toc" to "## Version: 1.0\n",
            "Loose/readme.txt" to "not an add-on")
        // a tiny HTTP/1.0 server (the Android test classpath has no JDK HttpServer): the release by tag, the asset with Range
        server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val base = { "http://127.0.0.1:${server.localPort}" }
        Thread {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                sock.use { so ->
                    val rd = so.getInputStream().bufferedReader()
                    val path = rd.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                    var range: Int? = null
                    while (true) { val h = rd.readLine() ?: break; if (h.isEmpty()) break
                        Regex("""(?i)^Range:\s*bytes=(\d+)-""").find(h)?.let { range = it.groupValues[1].toInt() } }
                    if (path.startsWith("/repos/")) apiCalls++
                    val (code, body) = when {
                        path.startsWith("/repos/o/r/releases/tags/") -> 200 to ("{\"tag_name\":\"t/v1.0\",\"assets\":[{\"name\":\"Test-1.0.zip\"," +
                            "\"size\":${zipBytes.size},\"browser_download_url\":\"${base()}/dl/Test-1.0.zip\"}]}").toByteArray()
                        path.startsWith("/dl/") -> { val f = range ?: 0; served += zipBytes.size - f
                            (if (f > 0) 206 else 200) to zipBytes.copyOfRange(f, zipBytes.size) }
                        else -> 404 to ByteArray(0)
                    }
                    val o = so.getOutputStream()
                    o.write(("HTTP/1.0 $code X" + CRLF + "Content-Length: ${body.size}" + CRLF + "Connection: close" + CRLF + CRLF).toByteArray()); o.write(body); o.flush()
                }
            }
        }.apply { isDaemon = true }.start()
        DependencyAddons.api = base()
        DependencyAddons.gameRunning = { false }
        ThirdPartyAddons.settleMs = 200
        ThirdPartyAddons.renameDir = realRename; ThirdPartyAddons.fileRename = realFileRename
        ThirdPartyAddons.start(object : ThirdPartyAddons.Store {
            override fun get(k: String, d: Boolean) = choices[k] ?: d
            override fun put(k: String, v: Boolean) { choices[k] = v }
        }, File(tmp, "private"))
    }

    @After fun down() {
        server.close(); tmp.deleteRecursively(); DependencyAddons.api = "https://api.github.com"
        ThirdPartyAddons.ALL = listOf(ThirdPartyAddons.SPOKEN, ThirdPartyAddons.POLESTAR, ThirdPartyAddons.QUESTTRAIL, ThirdPartyAddons.MANNERS)
        ThirdPartyAddons.renameDir = realRename; ThirdPartyAddons.fileRename = realFileRename; ThirdPartyAddons.settleMs = 5_000L
    }

    private val realRename = ThirdPartyAddons.renameDir
    private val realFileRename = ThirdPartyAddons.fileRename

    private val game get() = File(tmp, "_classic_beta_")
    private val addons get() = File(game, "Interface/AddOns")

    @Test fun nothingWithoutInstall() {
        val a = addon(zipBytes); ThirdPartyAddons.ALL = listOf(a)
        assertFalse(ThirdPartyAddons.coreWanted(a))
        ThirdPartyAddons.applyAtStart(game)
        assertFalse(File(addons, "TestA").exists())
        assertEquals(0L, served)
    }

    @Test fun installThenUninstall() {
        val a = addon(zipBytes); ThirdPartyAddons.ALL = listOf(a)
        File(addons, "TestA").mkdirs(); File(addons, "TestA/TestA.toc").writeText("## Version: 0.9\n")    // an old copy
        choices["t.core"] = true
        ThirdPartyAddons.fetchNow(a, "core")
        assertEquals(ThirdPartyAddons.Kind.DOWNLOADED, ThirdPartyAddons.status(a, "core").kind)
        val line = ThirdPartyAddons.applyAtStart(game)
        assertTrue(line, line.contains("Test 1.0 installed"))
        assertEquals("1.0", DependencyAddons.installedVersion(File(addons, "TestA")))
        assertTrue(File(addons, "TestB/TestB.toc").isFile)
        assertFalse("only add-on folders go in", File(addons, "Loose").exists())
        val backup = File(game, "Interface/AddOns-backup-dsforever")
        assertTrue("the old copy is kept", backup.listFiles()!!.any { it.name.startsWith("TestA-0.9-") })
        assertTrue(ThirdPartyAddons.coreInstalled(a))
        assertFalse("the download's room is given back", File(tmp, "private/dl/Test-1.0.zip").exists())
        // Uninstall: out to the backup at the next start, never deleted
        choices["t.core"] = false
        assertEquals(ThirdPartyAddons.Kind.REMOVING, ThirdPartyAddons.status(a, "core").kind)
        Thread.sleep(1100)                                              // a new backup stamp
        ThirdPartyAddons.applyAtStart(game)
        assertFalse(File(addons, "TestA").exists()); assertFalse(File(addons, "TestB").exists())
        assertTrue(backup.listFiles()!!.any { it.name.startsWith("TestA-1.0-") } && backup.listFiles()!!.any { it.name.startsWith("TestB-1.0-") })
        assertFalse(ThirdPartyAddons.coreInstalled(a))
    }

    @Test fun aWrongChecksumNeverGoesIn() {
        val a = addon(zipBytes, sha256 = "0".repeat(64)); ThirdPartyAddons.ALL = listOf(a)
        choices["t.core"] = true
        ThirdPartyAddons.fetchNow(a, "core")
        assertEquals(ThirdPartyAddons.Kind.FAILED, ThirdPartyAddons.status(a, "core").kind)
        assertFalse(File(tmp, "private/dl/Test-1.0.zip").exists())
    }

    @Test fun aStoppedDownloadResumes() {
        val a = addon(zipBytes); ThirdPartyAddons.ALL = listOf(a)
        choices["t.core"] = true
        val part = File(tmp, "private/dl/Test-1.0.zip.part"); part.parentFile.mkdirs()
        part.writeBytes(zipBytes.copyOfRange(0, 40))                    // 40 bytes came before it stopped
        ThirdPartyAddons.fetchNow(a, "core")
        assertEquals(zipBytes.size - 40L, served)                       // only the rest was asked for
        assertEquals(ThirdPartyAddons.Kind.DOWNLOADED, ThirdPartyAddons.status(a, "core").kind)
    }

    @Test fun theGameRunningWaits() {
        val a = addon(zipBytes); ThirdPartyAddons.ALL = listOf(a)
        choices["t.core"] = true; ThirdPartyAddons.fetchNow(a, "core")
        DependencyAddons.gameRunning = { true }
        ThirdPartyAddons.applyAtStart(game)
        assertFalse(File(addons, "TestA").exists())
        DependencyAddons.gameRunning = { false }
        ThirdPartyAddons.applyAtStart(game)
        assertTrue(File(addons, "TestA").exists())
    }

    /** test40: the questing ones install from Add-ons too ([ThirdPartyAddons.install], Map's picker); "in use" is display only;
     *  switched away they stay (the user: "Keep it installed"); only their Uninstall takes them out */
    @Test fun usedElsewhereInstallsHereAndStays() {
        var chosen = false
        val a = addon(zipBytes).copy(inUse = { chosen }, usedIn = "Map › Quest tracking")
        ThirdPartyAddons.ALL = listOf(a)
        ThirdPartyAddons.applyAtStart(game)
        assertFalse("choosing it elsewhere installs nothing by itself", File(addons, "TestA").exists())
        ThirdPartyAddons.install(a); ThirdPartyAddons.install(a)          // twice: harmless
        ThirdPartyAddons.fetchNow(a, "core")
        ThirdPartyAddons.applyAtStart(game)
        assertTrue(File(addons, "TestA").exists())
        assertTrue(ThirdPartyAddons.status(a, "core").line.contains("not in use"))
        chosen = true
        assertEquals("Installed: in use", ThirdPartyAddons.status(a, "core").line)
        chosen = false
        ThirdPartyAddons.applyAtStart(game)
        assertTrue("kept installed", File(addons, "TestA").exists())
        ThirdPartyAddons.setCore(a, false)                                 // its Uninstall
        assertEquals(ThirdPartyAddons.Kind.REMOVING, ThirdPartyAddons.status(a, "core").kind)
        ThirdPartyAddons.applyAtStart(game)
        assertFalse(File(addons, "TestA").exists())
    }

    /** QuestTrail 3.1.0 -> 3.2.1 (the user: "Update QuestTrail on my Thor"): a new pin updates an installed older version by itself,
     *  its row saying so; downloaded, in at the next start, the old copy to the backup */
    private fun installedThenRepinned(): ThirdPartyAddons.Addon {
        val base = "http://127.0.0.1:${server.localPort}"
        val a = addon(zipBytes).let { it.copy(core = it.core.copy(url = "$base/dl/Test-1.0.zip")) }
        ThirdPartyAddons.ALL = listOf(a)
        choices["t.core"] = true; ThirdPartyAddons.fetchNow(a, "core"); ThirdPartyAddons.applyAtStart(game)
        assertTrue(ThirdPartyAddons.coreInstalled(a))
        val b = a.copy(version = "1.1", core = a.core.copy(tag = "t/v1.1"))
        ThirdPartyAddons.ALL = listOf(b)
        return b
    }

    @Test fun aNewPinUpdatesTheInstalledVersion() {
        val b = installedThenRepinned()
        assertFalse(ThirdPartyAddons.coreInstalled(b))
        assertFalse("an update never takes it out", ThirdPartyAddons.goesOut(b, "core"))
        assertTrue(ThirdPartyAddons.status(b, "core").line, ThirdPartyAddons.status(b, "core").line.startsWith("Update to 1.1"))
        Thread.sleep(1100)                                              // a new backup stamp
        val line = ThirdPartyAddons.applyAtStart(game)                  // small: fetched in the start step itself
        assertTrue(line, line.contains("Test 1.1 installed"))
        assertTrue(ThirdPartyAddons.coreInstalled(b))
        assertEquals("Installed", ThirdPartyAddons.status(b, "core").line)
        assertTrue(File(game, "Interface/AddOns-backup-dsforever").listFiles()!!.any { it.name.startsWith("TestA-1.0-") })
    }

    @Test fun anOlderVersionStillGoesOutOnUninstall() {
        val b = installedThenRepinned()
        ThirdPartyAddons.setCore(b, false)
        assertEquals(ThirdPartyAddons.Kind.REMOVING, ThirdPartyAddons.status(b, "core").kind)
        ThirdPartyAddons.applyAtStart(game)
        assertFalse(File(addons, "TestA").exists())
    }

    @Test fun aDirectUrlNeedsNoReleaseApi() {
        val base = "http://127.0.0.1:${server.localPort}"
        val a = addon(zipBytes).let { it.copy(core = it.core.copy(url = "$base/dl/Test-1.0.zip")) }
        ThirdPartyAddons.ALL = listOf(a)
        choices["t.core"] = true
        ThirdPartyAddons.fetchNow(a, "core")
        assertEquals(ThirdPartyAddons.Kind.DOWNLOADED, ThirdPartyAddons.status(a, "core").kind)
        assertEquals(0, apiCalls)
    }

    @Test fun spokenAndPolestarArePinned() {
        for (a in listOf(ThirdPartyAddons.SPOKEN, ThirdPartyAddons.POLESTAR, ThirdPartyAddons.QUESTTRAIL, ThirdPartyAddons.MANNERS)) for ((_, p) in a.pkgs()) {
            assertTrue(p.asset, p.sha256.matches(Regex("[0-9a-f]{64}")) && p.bytes > 0 && p.folders.isNotEmpty())
        }
        assertEquals(1540, ThirdPartyAddons.SPOKEN.modules.first { it.id == "quests" }.pkg.mb)
        assertEquals("3.2.1", ThirdPartyAddons.QUESTTRAIL.core.tag)
        assertEquals(7_656_314L, ThirdPartyAddons.QUESTTRAIL.core.bytes)
        assertEquals(listOf("Manners"), ThirdPartyAddons.MANNERS.core.folders)
        assertEquals(3_654_185L, ThirdPartyAddons.MANNERS.core.bytes)
    }

    // ---------------------------------------------------------------- issues #135 #83 #117 #139 (2026-11-11)
    private val staging get() = File(game, "Interface/AddOns-staging-dsforever")
    private val backupDir get() = File(game, "Interface/AddOns-backup-dsforever")

    private fun downloaded(): ThirdPartyAddons.Addon {
        val a = addon(zipBytes); ThirdPartyAddons.ALL = listOf(a)
        choices["t.core"] = true; ThirdPartyAddons.fetchNow(a, "core")
        assertEquals(ThirdPartyAddons.Kind.DOWNLOADED, ThirdPartyAddons.status(a, "core").kind)
        return a
    }

    /** #135 / #83: Android's FUSE refused the SpokenQuestsAudioAlliance folder's rename, both in and to the backup: file by file then */
    @Test fun aRefusedFolderRenameMovesFileByFile() {
        val a = downloaded()
        File(addons, "TestA/sub").mkdirs(); File(addons, "TestA/TestA.toc").writeText("## Version: 0.9\n"); File(addons, "TestA/sub/old.lua").writeText("old")
        var refused = 0
        ThirdPartyAddons.renameDir = { _, _ -> refused++; "Operation not permitted" }
        val line = ThirdPartyAddons.applyAtStart(game)
        assertTrue(line, line.contains("Test 1.0 installed"))
        assertTrue(ThirdPartyAddons.coreInstalled(a))
        assertTrue("the folder rename was tried first", refused >= 3)                 // the backup, TestA, TestB
        assertEquals("1.0", DependencyAddons.installedVersion(File(addons, "TestA")))
        assertEquals("x=1", File(addons, "TestA/a.lua").readText())
        assertFalse("the new copy is whole: nothing of the old one mixed in", File(addons, "TestA/sub").exists())
        val old = backupDir.listFiles()!!.single { it.name.startsWith("TestA-0.9-") }
        assertEquals("old", File(old, "sub/old.lua").readText())                       // the old copy kept whole
        assertFalse("no staging left behind", staging.exists())
    }

    /** #135: a move that failed is recorded for that package's content and NOT tried again on every Play; Try again (Settings) or a
     *  new pin tries it again. The staging copy never stays behind (#139) */
    @Test fun aFailedMoveWaitsForTryAgain() {
        val a = downloaded()
        File(addons, "TestA").mkdirs(); File(addons, "TestA/TestA.toc").writeText("## Version: 0.9\n")
        backupDir.parentFile.mkdirs(); backupDir.writeText("in the way")                 // the backup cannot be made at all
        var tries = 0
        ThirdPartyAddons.renameDir = { _, _ -> tries++; "refused" }
        val first = ThirdPartyAddons.applyAtStart(game)
        assertTrue(first, first.contains("Test skipped (the old TestA could not be moved to the backup"))
        assertFalse("no staging left behind", staging.exists())
        assertEquals("1.0 is not in", "0.9", DependencyAddons.installedVersion(File(addons, "TestA")))
        val st = ThirdPartyAddons.status(a, "core")
        assertEquals(ThirdPartyAddons.Kind.FAILED, st.kind)
        assertTrue(st.line, st.line.startsWith("Could not go in (") && st.line.endsWith("Try again"))
        assertTrue(ThirdPartyAddons.anyFailed(a))
        val t1 = tries
        val second = ThirdPartyAddons.applyAtStart(game)                                  // the next Play
        assertTrue(second, second.contains("Test not installed (") && second.contains("Try again in Settings > Add-ons"))
        assertEquals("not unpacked or moved again", t1, tries)
        assertTrue("the download is kept for the Try again", File(tmp, "private/dl/Test-1.0.zip").isFile)
        // Settings > Add-ons' Try again, the obstacle gone: in at the next start
        backupDir.delete(); ThirdPartyAddons.renameDir = realRename
        ThirdPartyAddons.retry(a)
        assertFalse(ThirdPartyAddons.anyFailed(a))
        assertEquals(ThirdPartyAddons.Kind.DOWNLOADED, ThirdPartyAddons.status(a, "core").kind)
        val third = ThirdPartyAddons.applyAtStart(game)
        assertTrue(third, third.contains("Test 1.0 installed"))
    }

    @Test fun aNewPinOrSwitchingOnTriesAFailedMoveAgain() {
        val a = downloaded()
        File(addons, "TestA").mkdirs(); File(addons, "TestA/TestA.toc").writeText("## Version: 0.9\n")
        backupDir.parentFile.mkdirs(); backupDir.writeText("in the way")
        ThirdPartyAddons.renameDir = { _, _ -> "refused" }
        ThirdPartyAddons.applyAtStart(game)
        assertTrue(ThirdPartyAddons.anyFailed(a))
        val b = a.copy(core = a.core.copy(sha256 = "1".repeat(64)))                       // other content under the same name
        ThirdPartyAddons.ALL = listOf(b)
        assertFalse(ThirdPartyAddons.anyFailed(b))
        // switching it on again (Install / a module) clears it too
        ThirdPartyAddons.ALL = listOf(a)
        assertTrue(ThirdPartyAddons.anyFailed(a))
        ThirdPartyAddons.setCore(a, true)
        assertFalse(ThirdPartyAddons.anyFailed(a))
    }

    /** #135: the game started right after the unpack: the complete staged copy is kept and used at the next start, never unpacked twice */
    @Test fun aCompleteStagedCopyIsNotUnpackedAgain() {
        val a = downloaded()
        var calls = 0
        DependencyAddons.gameRunning = { ++calls >= 3 }               // 1 the settle, 2 before the move, 3 after the unpack: the game started
        val line = ThirdPartyAddons.applyAtStart(game)
        assertTrue(line, line.contains("Test skipped (the game started"))
        assertFalse(ThirdPartyAddons.anyFailed(a))
        assertTrue("the staged copy kept", File(staging, "t-core/TestA/a.lua").isFile)
        File(staging, "t-core/TestA/a.lua").writeText("staged")       // marks the staged copy: an unpack would write x=1 again
        DependencyAddons.gameRunning = { false }
        val next = ThirdPartyAddons.applyAtStart(game)
        assertTrue(next, next.contains("Test 1.0 installed"))
        assertEquals("staged", File(addons, "TestA/a.lua").readText())
        assertFalse(staging.exists())
    }

    /** #139 ("addons not moving from staging"): our own staging leftovers (no complete mark, or not wanted) go at the start */
    @Test fun leftoverStagingIsCleared() {
        val a = addon(zipBytes); ThirdPartyAddons.ALL = listOf(a)
        File(staging, "t-core/TestA").mkdirs(); File(staging, "t-core/TestA/a.lua").writeText("half")
        File(staging, "QuestieDB").mkdirs()                                               // not ours here: left alone
        ThirdPartyAddons.applyAtStart(game)
        assertFalse(File(staging, "t-core").exists())
        assertTrue(File(staging, "QuestieDB").exists())
    }

    /** #139: the start step ran 145 ms after "game: ended" while the game screen still said up: it waits for that to settle */
    @Test fun theGameEndingSettlesFirst() {
        downloaded()
        ThirdPartyAddons.settleMs = 3_000
        val until = System.currentTimeMillis() + 400
        DependencyAddons.gameRunning = { System.currentTimeMillis() < until }
        val line = ThirdPartyAddons.applyAtStart(game)
        assertTrue(line, line.contains("Test 1.0 installed"))
    }

    @Test fun theGameIsDownOnceItEndedAfterItsScreenCameUp() {
        assertFalse(DependencyAddons.gameUpNow(up = false, startSec = 100, endedAtMs = 0))
        assertTrue("up, never ended", DependencyAddons.gameUpNow(up = true, startSec = 100, endedAtMs = 0))
        assertFalse("ended after it came up: down, its screen not gone yet", DependencyAddons.gameUpNow(true, 100, 100_500))
        assertTrue("an older end, a new start (a stream after a local run)", DependencyAddons.gameUpNow(true, 200, 100_500))
    }

    /** the start step's status text, one line per step (#117 #135: no more "Preparing your game art..." over all of it) */
    @Test fun eachStepSaysWhatItDoes() {
        downloaded()
        val steps = ArrayList<String>()
        ThirdPartyAddons.applyAtStart(game, onStep = { steps.add(it) })
        assertEquals(listOf("Installing add-ons: Test..."), steps)
        choices["t.core"] = false
        steps.clear(); ThirdPartyAddons.applyAtStart(game, onStep = { steps.add(it) })
        assertEquals(listOf("Removing add-ons: Test..."), steps)
    }

    // ---------------------------------------------------------------- the review of the #135 fix (2026-11-11)
    private fun oldTestA() {
        File(addons, "TestA/sub").mkdirs(); File(addons, "TestA/TestA.toc").writeText("## Version: 0.9\n")
        File(addons, "TestA/old1.lua").writeText("one"); File(addons, "TestA/sub/old2.lua").writeText("two")
    }
    private fun assertOldTestAWhole() {
        assertEquals("0.9", DependencyAddons.installedVersion(File(addons, "TestA")))
        assertEquals("one", File(addons, "TestA/old1.lua").readText()); assertEquals("two", File(addons, "TestA/sub/old2.lua").readText())
        assertFalse("nothing of the new copy", File(addons, "TestA/a.lua").exists())
        assertFalse("no other folder of the package left in", File(addons, "TestB").exists())
        assertFalse("no half copy in the backup", backupDir.listFiles()?.any { it.name.startsWith("TestA-") && it.walk().any { f -> f.isFile } } ?: false)
    }
    private val sep = File.separator

    /** the new folder's file-by-file move fails on its 2nd file: AddOns gets the WHOLE old copy back, nothing half new */
    @Test fun aMoveInFailingPartWayPutsTheOldCopyBack() {
        val a = downloaded(); oldTestA()
        ThirdPartyAddons.renameDir = { _, _ -> "refused" }
        var n = 0
        ThirdPartyAddons.fileRename = { s, d ->
            if (s.path.contains("t-core${sep}TestA${sep}") && ++n == 2) throw java.io.IOException("refused") else s.renameTo(d)
        }
        val line = ThirdPartyAddons.applyAtStart(game)
        assertTrue(line, line.contains("Test skipped (the new TestA could not be moved in"))
        assertOldTestAWhole()
        assertFalse(ThirdPartyAddons.coreInstalled(a)); assertTrue(ThirdPartyAddons.anyFailed(a))
        assertFalse("no staging left behind", staging.exists())
    }

    /** the old folder's move to the backup fails on its 2nd file: the old copy stays whole in AddOns, the new one not in */
    @Test fun aBackupFailingPartWayLeavesTheOldCopyWhole() {
        val a = downloaded(); oldTestA()
        ThirdPartyAddons.renameDir = { _, _ -> "refused" }
        var n = 0
        ThirdPartyAddons.fileRename = { s, d ->
            if (s.path.contains("AddOns${sep}TestA${sep}") && ++n == 2) throw java.io.IOException("refused") else s.renameTo(d)
        }
        val line = ThirdPartyAddons.applyAtStart(game)
        assertTrue(line, line.contains("Test skipped (the old TestA could not be moved to the backup"))
        assertOldTestAWhole()
        assertFalse(ThirdPartyAddons.coreInstalled(a))
    }

    /** the mark goes before the first folder leaves staging; a marked copy missing a folder (a move cut short) is unpacked again */
    @Test fun aStagedCopyCutShortIsUnpackedAgain() {
        val a = downloaded()
        var calls = 0
        DependencyAddons.gameRunning = { ++calls >= 3 }               // the game started after the unpack: the copy is kept, marked
        ThirdPartyAddons.applyAtStart(game)
        assertTrue(File(staging, "t-core/.dsf-staged").isFile)
        File(staging, "t-core/TestB").deleteRecursively()               // as if TestB had already gone in when the process died
        File(staging, "t-core/TestA/a.lua").writeText("staged")
        DependencyAddons.gameRunning = { false }
        var markDuringMove: Boolean? = null
        ThirdPartyAddons.renameDir = { s, d -> if (markDuringMove == null) markDuringMove = File(staging, "t-core/.dsf-staged").exists(); realRename(s, d) }
        val line = ThirdPartyAddons.applyAtStart(game)
        assertTrue(line, line.contains("Test 1.0 installed"))
        assertEquals("unpacked again", "x=1", File(addons, "TestA/a.lua").readText())
        assertTrue("the whole package in", File(addons, "TestB/TestB.toc").isFile)
        assertEquals("no mark while folders leave staging", false, markDuringMove)
        assertTrue(ThirdPartyAddons.coreInstalled(a))
    }

    /** the launcher's own start step holds the run (no status line): a Play waiting for it shows that run's step */
    @Test fun aWaitingPlayShowsTheRunningStep() {
        downloaded()
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        ThirdPartyAddons.renameDir = { s, d -> entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS); realRename(s, d) }
        val first = Thread { ThirdPartyAddons.applyAtStart(game) }.apply { start() }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val steps = java.util.Collections.synchronizedList(ArrayList<String>())
        val second = Thread { ThirdPartyAddons.applyAtStart(game, onStep = { steps.add(it) }) }.apply { start() }
        val until = System.currentTimeMillis() + 3_000
        while (steps.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
        release.countDown(); first.join(5_000); second.join(5_000)
        assertEquals(listOf("Installing add-ons: Test..."), steps.toList())
        assertEquals(null, ThirdPartyAddons.currentStep)
    }
}
