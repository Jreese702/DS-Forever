package app.gamenative.ui.screen.wow

import app.gamenative.ui.screen.wow.WowClientDownloader.PatchFail
import app.gamenative.ui.screen.wow.WowClientDownloader.PatchWhy
import java.io.File
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Issue 150 ("Install failed: Blizzard's patch server did not answer"), off-device: the direct install's patch check says WHY in the
 * player's words (the name not found, the connection blocked, the secure connection, a timeout, the server's error code) and tries again
 * twice before it gives up. A local server plays Blizzard's patch server; no network.
 */
class WowPatchAnswerTest {
    private lateinit var server: MockWebServer
    private val answers = ArrayDeque<Int>()          // the versions table's next HTTP codes (empty = 200)
    private var versionsAsked = 0
    private val savedBackoff = WowClientDownloader.patchBackoffMs
    private val statuses = mutableListOf<String>()

    private val versions = "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|KeyRing!HEX:16|BuildId!DEC:4|VersionsName!String:0|" +
        "ProductConfig!HEX:16\n## seqn = 1\nus|0123456789abcdef0123456789abcdef|fedcba9876543210fedcba9876543210||70532|1.60.1.70532|2ced73c1\n"

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path.orEmpty()) {
                "/patch/wowf/versions" -> {
                    synchronized(answers) { versionsAsked++ }
                    val code = synchronized(answers) { answers.removeFirstOrNull() } ?: 200
                    if (code == 200) MockResponse().setResponseCode(200).setBody(versions) else MockResponse().setResponseCode(code)
                }
                "/patch/wowf/cdns" -> MockResponse().setResponseCode(200).setBody("Name!STRING:0|Path!STRING:0|Hosts!STRING:0|Servers!STRING:0\n" +
                    "us|tpr/wow|127.0.0.1|http://127.0.0.1/?maxhosts=4\n")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        WowClientDownloader.patchUrlFor = { "http://127.0.0.1:${server.port}/patch/${it.code}" }
        WowClientDownloader.patchBackoffMs = listOf(0L, 0L)
    }

    @After fun tearDown() {
        WowClientDownloader.patchUrlFor = { it.patchUrl }
        WowClientDownloader.patchBackoffMs = savedBackoff
        server.shutdown()
    }

    private fun target() = WowClientDownloader.installTarget(mapOf("Branch" to "us")) { statuses += it }

    private fun failure(): WowClientDownloader.PatchServerException = try {
        target(); fail("the install's check must fail"); throw AssertionError()
    } catch (e: WowClientDownloader.PatchServerException) { e }

    @Test fun aPassingErrorIsTriedAgain() {
        answers.addAll(listOf(503, 503))
        val t = target()
        assertEquals("1.60.1.70532", t.remoteVersion)
        assertEquals("the first try and two more", 3, versionsAsked)
        assertEquals(listOf("Checking the latest build... (try 2 of 3)", "Checking the latest build... (try 3 of 3)"), statuses)
    }

    @Test fun threeFailedTriesSayWhy() {
        answers.addAll(listOf(503, 503, 503))
        val e = failure()
        assertEquals(3, e.tries)
        assertEquals(PatchWhy(PatchFail.HTTP, 503), e.why)
        assertEquals("Blizzard's patch server did not answer: it sent back an error (HTTP 503). Try again later.", e.message)
        assertEquals("patch server: HTTP 503, 3 tries", e.detail)
    }

    @Test fun aRefusalThatWillNotPassIsNotRepeated() {
        answers.add(404)
        val e = failure()
        assertEquals("a 404 is not worth a second try", 1, versionsAsked)
        assertEquals(1, e.tries)
        assertEquals("patch server: HTTP 404, 1 try", e.detail)
    }

    @Test fun noServerAtAllIsABlockedConnection() {
        WowClientDownloader.patchUrlFor = { "http://127.0.0.1:1/patch/${it.code}" }
        val e = failure()
        assertEquals(3, e.tries)
        assertEquals(PatchFail.CONNECT, e.why.fail)
        assertTrue(e.message!!.startsWith(WowClientDownloader.PATCH_NO_ANSWER + ": the connection was blocked"))
    }

    @Test fun eachFailureHasItsKind() {
        assertEquals(PatchFail.DNS, WowClientDownloader.patchWhy(java.net.UnknownHostException("x")).fail)
        assertEquals(PatchFail.CONNECT, WowClientDownloader.patchWhy(java.net.ConnectException("x")).fail)
        assertEquals(PatchFail.CONNECT, WowClientDownloader.patchWhy(java.net.NoRouteToHostException("x")).fail)
        assertEquals(PatchFail.TLS, WowClientDownloader.patchWhy(javax.net.ssl.SSLHandshakeException("x")).fail)
        assertEquals(PatchFail.TLS, WowClientDownloader.patchWhy(java.io.IOException("x", java.security.cert.CertificateException("y"))).fail)
        assertEquals(PatchFail.TIMEOUT, WowClientDownloader.patchWhy(java.net.SocketTimeoutException("x")).fail)
        assertEquals("OkHttp's call timeout", PatchFail.TIMEOUT, WowClientDownloader.patchWhy(java.io.InterruptedIOException("timeout")).fail)
        assertEquals("a wrapped cause", PatchFail.DNS, WowClientDownloader.patchWhy(java.io.IOException("x", java.net.UnknownHostException("y"))).fail)
        assertEquals(PatchFail.OTHER, WowClientDownloader.patchWhy(java.io.IOException("x")).fail)
        // the tries: a network failure and a server's passing trouble, not another refusal or a missing build
        assertTrue(WowClientDownloader.patchRetries(PatchWhy(PatchFail.TIMEOUT)))
        assertTrue(WowClientDownloader.patchRetries(PatchWhy(PatchFail.HTTP, 500)))
        assertTrue(WowClientDownloader.patchRetries(PatchWhy(PatchFail.HTTP, 429)))
        assertFalse(WowClientDownloader.patchRetries(PatchWhy(PatchFail.HTTP, 403)))
        assertFalse(WowClientDownloader.patchRetries(PatchWhy(PatchFail.NO_BUILD)))
    }

    @Test fun everyLineIsTranslated() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { File(it) }.first { it.isDirectory }
        for (lang in listOf("es", "fr")) {
            val json = File(dir, "$lang.json").readText(Charsets.UTF_8)
            for (f in PatchFail.values()) {
                // the line as the top screen gets it (WoWForeverScreen: "Install failed: " + the message); HTTP's code is a template's %s
                val key = "Install failed: " + WowClientDownloader.patchFailText(PatchWhy(f, 503)).replace("HTTP 503", "HTTP %s")
                assertTrue("$lang: $key", "\"$key\":" in json)
            }
            assertTrue(lang, "\"Checking the latest build... (try %s of %s)\":" in json)
        }
        assertTrue("WoWForeverScreen adds that prefix", "errorMessage = \"Install failed: \${e.message}\"" in
            File(listOf("src/main/java/app/gamenative/ui/screen/wow", "app/src/main/java/app/gamenative/ui/screen/wow").first { File(it).isDirectory },
                "WoWForeverScreen.kt").readText())
    }
}
