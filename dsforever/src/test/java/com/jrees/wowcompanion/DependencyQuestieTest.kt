package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * GitHub #293 (1.0.32: "available quest won't show in the map"; every 1.0.32 report: "QuestieDB skipped (no QuestieDB-Forever.zip in
 * v1.1.1)"): QuestieDB has named its packages with the version since v1.0.11 ("QuestieDB-1.1.2-Forever.zip"). The Forever package is
 * found by either spelling, never another flavour's, the "-all" one or a preview's, and a local "GitHub" release laid out as the real
 * v1.1.2 (its asset names as listed by the API) installs it.
 */
class DependencyQuestieTest {
    private val questie = DependencyAddons.DEPS.first { it.folder == "QuestieDB" }
    private fun named(vararg n: String) = n.map { JSONObject().put("name", it) }

    @Test fun theForeverPackageByEitherName() {
        val v112 = named("QuestieDB-1.1.2-all.zip", "QuestieDB-1.1.2-Cata.zip", "QuestieDB-1.1.2-Forever.zip", "QuestieDB-1.1.2-Mists.zip",
            "QuestieDB-1.1.2-TBC.zip", "QuestieDB-1.1.2-Vanilla.zip", "QuestieDB-1.1.2-Wrath.zip", "release.json", "RELEASE_NOTES.md")
        assertEquals("QuestieDB-1.1.2-Forever.zip", DependencyAddons.assetOf(questie, v112)?.optString("name"))
        assertEquals("QuestieDB-Forever.zip", DependencyAddons.assetOf(questie, named("QuestieDB-Forever.zip", "QuestieDB.zip"))?.optString("name"))
        assertEquals("QuestieDB-1.0.16-Forever.zip", DependencyAddons.assetOf(questie, named("QuestieDB-1.0.16-Forever.zip"))?.optString("name"))
        assertNull("a preview build's package is never taken",
            DependencyAddons.assetOf(questie, named("QuestieDB-1.1.1-dev.4588c44-Forever.zip", "QuestieDB-1.1.1-dev.4588c44-all.zip")))
        assertNull("another flavour's or the all-in-one package is never taken",
            DependencyAddons.assetOf(questie, named("QuestieDB-1.1.2-Vanilla.zip", "QuestieDB-1.1.2-all.zip", "QuestieDB-Forever.zip.sha256")))
    }

    private val CRLF = "\r\n"
    private lateinit var server: ServerSocket
    private lateinit var tmp: File
    private var zipBytes = ByteArray(0)
    private val realApi = DependencyAddons.api
    private val realRunning = DependencyAddons.gameRunning

    private fun zipOf(vararg files: Pair<String, String>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> for ((n, t) in files) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return bo.toByteArray()
    }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Before fun up() {
        tmp = Files.createTempDirectory("questie").toFile()
        zipBytes = zipOf("QuestieDB/QuestieDB.toc" to "## Version: 1.1.2\n", "QuestieDB/Database/Forever/quests.lua" to "x=1")
        server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${server.localPort}"
        val names = listOf("all", "Cata", "Forever", "Mists", "TBC", "Vanilla", "Wrath")
        val assets = names.joinToString(",") { f ->
            val n = "QuestieDB-1.1.2-$f.zip"
            if (f == "Forever") "{\"name\":\"$n\",\"size\":${zipBytes.size},\"digest\":\"sha256:${sha(zipBytes)}\",\"browser_download_url\":\"$base/dl/$n\"}"
            else "{\"name\":\"$n\",\"size\":1,\"digest\":\"sha256:${"0".repeat(64)}\",\"browser_download_url\":\"$base/dl/$n\"}"
        }
        Thread {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                sock.use { so ->
                    val rd = so.getInputStream().bufferedReader()
                    val path = rd.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                    while (true) { val h = rd.readLine() ?: break; if (h.isEmpty()) break }
                    val (code, body) = when (path) {
                        "/repos/Questie/QuestieDB/releases/latest" -> 200 to "{\"tag_name\":\"v1.1.2\",\"assets\":[$assets]}".toByteArray()
                        "/dl/QuestieDB-1.1.2-Forever.zip" -> 200 to zipBytes
                        else -> 404 to ByteArray(0)
                    }
                    val o = so.getOutputStream()
                    o.write(("HTTP/1.0 $code X" + CRLF + "Content-Length: ${body.size}" + CRLF + "Connection: close" + CRLF + CRLF).toByteArray())
                    o.write(body); o.flush()
                }
            }
        }.apply { isDaemon = true }.start()
        DependencyAddons.api = base
        DependencyAddons.gameRunning = { false }
    }

    @After fun down() {
        server.close(); tmp.deleteRecursively()
        DependencyAddons.api = realApi; DependencyAddons.gameRunning = realRunning
    }

    @Test fun theVersionedPackageInstalls() {
        val game = File(tmp, "_forever_")
        val addons = File(game, "Interface/AddOns")
        File(addons, "QuestieDB").mkdirs(); File(addons, "QuestieDB/QuestieDB.toc").writeText("## Version: 1.1.0\n")    // #305's kept copy
        val line = DependencyAddons.run(game, File(tmp, "cache"), File(tmp, "state.json"))
        assertTrue(line, line.contains("QuestieDB updated 1.1.0 -> 1.1.2"))
        assertEquals("1.1.2", DependencyAddons.installedVersion(File(addons, "QuestieDB")))
        assertTrue(File(addons, "QuestieDB/Database/Forever/quests.lua").isFile)
        assertTrue("the old copy is kept", File(game, "Interface/${DependencyAddons.BACKUP}").listFiles()!!.any { it.name.startsWith("QuestieDB-1.1.0-") })
    }

    /** #293 review: an older app's recent "skipped" line (no rule stamp) is not kept for 6 h: the first start after the update checks */
    @Test fun anOlderAppsSkipIsCheckedAgainAtOnce() {
        val game = File(tmp, "_forever_")
        File(game, "Interface/AddOns").mkdirs()
        val state = File(tmp, "state.json")
        val now = System.currentTimeMillis()
        val old = "QuestieDB skipped (no QuestieDB-Forever.zip in v1.1.1)"
        state.writeText(JSONObject().put("QuestieDB", JSONObject().put("at", now - 60_000L).put("line", old).put("installed", "")
            .put("pending", false)).toString())
        val line = DependencyAddons.run(game, File(tmp, "cache"), state, now)
        assertTrue(line, line.contains("QuestieDB 1.1.2 installed"))
        assertEquals(DependencyAddons.STATE_RULE, JSONObject(state.readText()).getJSONObject("QuestieDB").getInt("rule"))
        // this app's own line, a minute later: kept, no new check
        val again = DependencyAddons.run(game, File(tmp, "cache"), state, now + 60_000L)
        assertTrue(again, again.contains("QuestieDB 1.1.2 installed"))
    }
}
