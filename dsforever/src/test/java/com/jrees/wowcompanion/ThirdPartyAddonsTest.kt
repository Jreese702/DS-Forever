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
        ThirdPartyAddons.start(object : ThirdPartyAddons.Store {
            override fun get(k: String, d: Boolean) = choices[k] ?: d
            override fun put(k: String, v: Boolean) { choices[k] = v }
        }, File(tmp, "private"))
    }

    @After fun down() {
        server.close(); tmp.deleteRecursively(); DependencyAddons.api = "https://api.github.com"
        ThirdPartyAddons.ALL = listOf(ThirdPartyAddons.SPOKEN, ThirdPartyAddons.POLESTAR, ThirdPartyAddons.QUESTTRAIL)
    }

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
        for (a in listOf(ThirdPartyAddons.SPOKEN, ThirdPartyAddons.POLESTAR, ThirdPartyAddons.QUESTTRAIL)) for ((_, p) in a.pkgs()) {
            assertTrue(p.asset, p.sha256.matches(Regex("[0-9a-f]{64}")) && p.bytes > 0 && p.folders.isNotEmpty())
        }
        assertEquals(1540, ThirdPartyAddons.SPOKEN.modules.first { it.id == "quests" }.pkg.mb)
    }
}
