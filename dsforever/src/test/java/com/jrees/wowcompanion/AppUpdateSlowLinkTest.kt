package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The app's self-update on slow links (the user, 2026-11-11: updates on slow links), off-device: the stall rule instead of a deadline,
 * the resume's Range math, a stopped download kept and resumed, the releases-page fallback when the API is limited, the day-old cache.
 */
class AppUpdateSlowLinkTest {
    private lateinit var server: ServerSocket
    private lateinit var tmp: File
    private var base = ""
    private var apk = ByteArray(0)
    private val ranges = mutableListOf<String?>()
    private var cutAfter = -1                          // the asset route closes after this many bytes on the next request (-1 = never)
    private var apiCode = 200

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 255) }

    /** what a route answers: code, headers, body, and how many body bytes are really sent before the socket closes (-1 = all) */
    private data class Answer(val code: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0), val cut: Int = -1)

    private fun route(path: String, range: String?): Answer {
        val m = range?.let { Regex("""bytes=(\d+)-(\d*)""").find(it) }
        return when (path) {
            "/api/repos/o/r/releases/latest" ->
                if (apiCode != 200) Answer(apiCode, mapOf("X-RateLimit-Remaining" to "0"), "limited".toByteArray())
                else Answer(200, body = ("""{"tag_name":"v1.0.5","body":"n","assets":[{"name":"DS-Forever-1.0.5.apk","size":${apk.size},""" +
                    """"browser_download_url":"$base/web/o/r/releases/download/v1.0.5/DS-Forever-1.0.5.apk","digest":"sha256:${sha(apk)}"}]}""").toByteArray())
            "/web/o/r/releases/latest" -> Answer(302, mapOf("Location" to "$base/web/o/r/releases/tag/v1.0.5"))
            "/web/o/r/releases/download/v1.0.5/SHA256SUMS" -> Answer(200, body = "${sha(apk)}  DS-Forever-1.0.5.apk\n".toByteArray())
            "/web/o/r/releases/download/v1.0.5/DS-Forever-1.0.5.apk" -> {
                ranges += range
                if (m == null) { val cut = cutAfter; cutAfter = -1; Answer(200, body = apk, cut = cut) }
                else {
                    val from = m.groupValues[1].toInt(); val to = m.groupValues[2].toIntOrNull() ?: (apk.size - 1)
                    Answer(206, mapOf("Content-Range" to "bytes $from-$to/${apk.size}"), apk.copyOfRange(from, to + 1))
                }
            }
            else -> Answer(404)
        }
    }

    @Before fun up() {
        tmp = Files.createTempDirectory("dsf-upd").toFile()
        apk = ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z ->
                z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(ByteArray(4000) { (it % 251).toByte() }); z.closeEntry()
                z.putNextEntry(ZipEntry("classes.dex")); z.write(ByteArray(200_000).also { java.util.Random(1).nextBytes(it) })   // incompressible: the zip stays ~200 KB; z.closeEntry()
            }
        }.toByteArray()
        // a tiny HTTP/1.0 server (the Android test classpath has no JDK HttpServer)
        server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        base = "http://127.0.0.1:${server.localPort}"
        Thread {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { sock.use { so ->
                    val rd = so.getInputStream().bufferedReader()
                    val path = rd.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                    var range: String? = null
                    while (true) { val h = rd.readLine() ?: break; if (h.isEmpty()) break
                        Regex("""(?i)^Range:\s*(.+)$""").find(h)?.let { range = it.groupValues[1].trim() } }
                    val a = route(path, range)
                    val o = so.getOutputStream()
                    val head = StringBuilder("HTTP/1.0 ${a.code} X\r\nContent-Length: ${a.body.size}\r\nConnection: close\r\n")
                    a.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
                    o.write(head.append("\r\n").toString().toByteArray())
                    o.write(a.body, 0, if (a.cut >= 0) a.cut else a.body.size); o.flush()
                } }
            }
        }.apply { isDaemon = true }.start()
        AppUpdate.api = "$base/api"
        AppUpdate.web = "$base/web"
    }

    @After fun down() {
        runCatching { server.close() }
        AppUpdate.api = "https://api.github.com"; AppUpdate.web = "https://github.com"
        tmp.deleteRecursively()
    }

    @Test fun stallIsNoBytesForTheLimitNotAWholeFileDeadline() {
        var now = 0L
        val s = AppUpdate.Stall(60_000) { now }
        now = 59_999; assertFalse(s.stalled())
        s.bytes()                                      // a byte at 59.999 s: another full minute from there
        now = 119_000; assertFalse(s.stalled())
        now = 119_999; assertTrue(s.stalled())
        // an hour-long download that never pauses a minute is never stalled
        val t = AppUpdate.Stall(60_000) { now }
        repeat(3600) { now += 1_000; t.bytes() }
        assertFalse(t.stalled())
    }

    @Test fun resumeRangeMath() {
        assertEquals(0L, AppUpdate.resumeFrom(0, 100))
        assertEquals(40L, AppUpdate.resumeFrom(40, 100))
        assertEquals(0L, AppUpdate.resumeFrom(100, 100))        // complete: nothing to ask
        assertEquals(0L, AppUpdate.resumeFrom(150, 100))        // longer than the release: start over
        assertEquals(Triple(40L, 99L, 100L), AppUpdate.contentRange("bytes 40-99/100"))
        assertEquals(Triple(0L, 0L, -1L), AppUpdate.contentRange("bytes 0-0/*"))
        assertNull(AppUpdate.contentRange("text/html"))
        assertTrue(AppUpdate.resumeOk(206, "bytes 40-99/100", 40, 100))
        assertFalse(AppUpdate.resumeOk(200, null, 40, 100))      // a whole body (a portal, a proxy) never appends
        assertFalse(AppUpdate.resumeOk(206, "bytes 0-99/100", 40, 100))
        assertFalse(AppUpdate.resumeOk(206, "bytes 40-80/100", 40, 100))
        assertFalse(AppUpdate.resumeOk(206, "bytes 40-99/120", 40, 100))
    }

    @Test fun latestRedirectAndSums() {
        assertEquals("v1.0.24", AppUpdate.tagFromLatest("https://github.com/Jreese702/DS-Forever/releases/tag/v1.0.24"))
        assertEquals("1.0.3", AppUpdate.tagFromLatest("/o/r/releases/tag/1.0.3?x=1"))
        assertNull(AppUpdate.tagFromLatest("https://github.com/o/r/releases"))       // no release yet
        assertNull(AppUpdate.tagFromLatest(null))
        val h = "a".repeat(64)
        assertEquals(h, AppUpdate.sumFor("$h  DS-Forever-1.0.5.apk\n${"b".repeat(64)} *other.zip", "DS-Forever-1.0.5.apk"))
        assertNull(AppUpdate.sumFor("$h  DS-Forever-1.0.4.apk", "DS-Forever-1.0.5.apk"))
    }

    @Test fun aRateLimitedApiFallsBackToTheReleasesPage() {
        apiCode = 403
        val logs = mutableListOf<String>()
        val c = AppUpdate.check("o/r", "1.0.4", "test", { logs += it })
        assertTrue(c.toString(), c is AppUpdate.Check.Available)
        val r = (c as AppUpdate.Check.Available).release
        assertEquals("1.0.5", r.version); assertEquals(apk.size.toLong(), r.size); assertEquals(sha(apk), r.sha256)
        assertTrue(logs.joinToString(), logs.any { "rate limit left: 0" in it })
        assertEquals(AppUpdate.Check.UpToDate("1.0.5"), AppUpdate.check("o/r", "1.0.5", "test"))
        // and the file it names downloads and checks against SHA256SUMS
        assertEquals(apk.size.toLong(), AppUpdate.download(r, File(tmp, "u"), "test", pause = {}).length())
    }

    @Test fun aDroppedDownloadResumesWithRangeAndChecksOut() {
        val r = (AppUpdate.check("o/r", "1.0.4", "test") as AppUpdate.Check.Available).release
        cutAfter = 50_000
        val f = AppUpdate.download(r, File(tmp, "u"), "test", pause = {})
        assertEquals(sha(apk), AppUpdate.sha256(f))
        assertEquals(listOf(null, "bytes=50000-"), ranges)
    }

    @Test fun aStoppedDownloadKeepsItsPartForTheNextPress() {
        val r = (AppUpdate.check("o/r", "1.0.4", "test") as AppUpdate.Check.Available).release
        val dir = File(tmp, "u").apply { mkdirs() }
        val part = File(dir, r.asset + ".part")
        part.writeBytes(apk.copyOfRange(0, 70_000))
        val dead = r.copy(url = "$base/nothing-here")                  // offline / no answer: a stall with a zero limit
        try { AppUpdate.download(dead, dir, "test", stallMs = 0, pause = {}); throw AssertionError("no error") } catch (_: IOException) {}
        assertEquals(70_000L, part.length())
        AppUpdate.clear(dir, keepPartial = true)                         // the startup clean-up keeps it too
        assertTrue(part.isFile)
        val f = AppUpdate.download(r, dir, "test", pause = {})
        assertEquals(sha(apk), AppUpdate.sha256(f))
        assertEquals(listOf("bytes=70000-"), ranges)
    }

    @Test fun theLastFoundUpdateIsOfferedADayWhenGitHubCannotBeReached() {
        val cache = File(tmp, "check.json")
        assertTrue(AppUpdate.check("o/r", "1.0.4", "test", cache = cache) is AppUpdate.Check.Available)
        server.close()
        val off = AppUpdate.check("o/r", "1.0.4", "test", cache = cache)
        assertTrue(off.toString(), off is AppUpdate.Check.Available)
        assertNull(AppUpdate.loadCached(cache, "1.0.5"))                                       // installed meanwhile
        assertNull(AppUpdate.loadCached(cache, "1.0.4", System.currentTimeMillis() + AppUpdate.CACHE_MS))  // a day old
    }
}
