package app.gamenative.ui.screen.wow

import java.io.File
import java.io.IOException
import java.nio.file.Files
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Issue #70 (2026-11-11, review of fix/cdn-heap): the streamed CDN path itself, driven through MockWebServer. Every failed answer
 * (cut short, a 200 to a Range, a body past its limit, an index that is not that index, a body too big for memory) leaves no file
 * and no ".part" behind, and the next host is tried.
 */
class WowCdnStreamTest {
    private lateinit var root: File
    private lateinit var bad: MockWebServer
    private lateinit var good: MockWebServer
    /** what [bad] answers to every request */
    private var badAnswer: () -> MockResponse = { MockResponse().setResponseCode(404) }
    /** what [good] answers, by request */
    private var goodAnswer: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }

    private val key = "0123456789abcdef0123456789abcdef"

    @Before fun setUp() {
        root = Files.createTempDirectory("dsf-cdn-stream").toFile()
        bad = MockWebServer().apply {
            dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = badAnswer() }
            start()
        }
        good = MockWebServer().apply {
            dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = goodAnswer(request) }
            start()
        }
    }

    @After fun tearDown() {
        bad.shutdown(); good.shutdown()
        root.deleteRecursively()
    }

    private fun host(s: MockWebServer) = "127.0.0.1:${s.port}"
    private fun cdn(vararg servers: MockWebServer) = WowClientDownloader.Cdn(servers.map { host(it) }, "tpr/wow")
    private val dest get() = File(root, "Data/client.blte")
    private fun nothingLeft() {
        assertFalse("dest was written", dest.exists())
        assertTrue("a .part was left", root.walkTopDown().none { it.name.endsWith(".part") })
    }
    private fun throws(what: String, block: () -> Unit): IOException {
        try { block() } catch (e: IOException) { return e }
        fail("$what did not throw"); throw AssertionError()
    }
    private fun bytes(n: Int) = ByteArray(n) { (it * 31 + 7).toByte() }
    private fun ok(b: ByteArray) = MockResponse().setResponseCode(200).setBody(Buffer().write(b))
    private fun ranged(b: ByteArray, from: Long, total: Long = 9999) =
        MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $from-${from + b.size - 1}/$total").setBody(Buffer().write(b))

    @Test fun aWholeBodyStreamsToItsFile() {
        val body = bytes(300_000)
        goodAnswer = { ok(body) }
        assertTrue(cdn(good).fetchTo("data", key, dest))
        assertTrue(dest.readBytes().contentEquals(body))
        assertTrue(root.walkTopDown().none { it.name.endsWith(".part") })
    }

    @Test fun aBodyCutShortIsAnErrorAndLeavesNothing() {
        // says 100 000 B, the connection drops part way: okhttp's stream fails, the .part goes
        badAnswer = { ok(bytes(100_000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) }
        throws("a cut body") { cdn(bad).fetchTo("data", key, dest) }
        nothingLeft()
    }

    @Test fun aWrongContentLengthIsAnErrorAndLeavesNothing() {
        // a header claiming more than is sent (a proxy that cut the body): never renamed over dest
        badAnswer = { ok(bytes(1000)).setHeader("Content-Length", "5000").setSocketPolicy(SocketPolicy.DISCONNECT_AT_END) }
        throws("a short body") { cdn(bad).fetchTo("data", key, dest) }
        nothingLeft()
    }

    @Test fun a200ToARangeIsAnErrorAndLeavesNothing() {
        val range = 40L until 50L
        badAnswer = { ok(bytes(10)) }                       // the right length, but a whole body (a portal, a proxy dropping Range)
        val e = throws("a 200 to a Range") { cdn(bad).fetchTo("data", key, dest, range) }
        assertTrue(e.message!!, e.message!!.startsWith("not the asked range"))
        nothingLeft()
    }

    @Test fun aBodyPastItsLimitIsStoppedAndLeavesNothing() {
        val range = 0L until 10L
        badAnswer = { ranged(bytes(20), 0) }                // a 206 that runs on past the asked 10 B
        val e = throws("a body past its range") { cdn(bad).fetchTo("data", key, dest, range) }
        assertTrue(e.message!!, e.message!!.contains("longer than 10"))
        nothingLeft()
    }

    @Test fun anIndexWithABadFooterIsAnErrorAndLeavesNothing() {
        badAnswer = { ok("<html>sign in to the hotel wifi</html>".toByteArray() + ByteArray(64)) }
        val index = File(root, "Data/indices/$key.index")
        val e = throws("a bad index") { cdn(bad).indexTo(key, index) }
        assertTrue(e.message!!, e.message!!.contains("is not that file"))
        assertFalse(index.exists())
        assertTrue(root.walkTopDown().none { it.name.endsWith(".part") })
    }

    @Test fun aFailedAnswerKeepsTheOldFile() {
        dest.parentFile!!.mkdirs(); dest.writeText("the old file")
        badAnswer = { ok(bytes(10)) }
        throws("a 200 to a Range") { cdn(bad).fetchTo("data", key, dest, 0L until 10L) }
        assertEquals("the old file", dest.readText())
        assertTrue(root.walkTopDown().none { it.name.endsWith(".part") })
    }

    @Test fun aFailedHostFallsThroughToTheNext() {
        val body = bytes(10)
        badAnswer = { ok(body) }                             // a 200 to the Range: not taken
        goodAnswer = { r ->
            assertEquals("bytes=40-49", r.getHeader("Range"))
            ranged(body, 40)
        }
        assertTrue(cdn(bad, good).fetchTo("data", key, dest, 40L until 50L))
        assertTrue(dest.readBytes().contentEquals(body))
        assertTrue(root.walkTopDown().none { it.name.endsWith(".part") })
    }

    @Test fun missingEverywhereIsFalseNotAFile() {
        assertFalse(cdn(bad, good).fetchTo("data", key, dest))
        nothingLeft()
        assertNull(cdn(bad, good).fetch("data", key))
    }

    @Test fun aBodyOverTheInMemoryMaxIsRefusedBeforeItIsRead() {
        val big = (com.jrees.wowcompanion.CdnStream.IN_MEMORY_MAX + 1).toInt()
        badAnswer = { ok(ByteArray(big)) }
        val e = throws("a body over IN_MEMORY_MAX") { cdn(bad).fetch("data", key) }
        assertTrue(e.message!!, e.message!!.contains("too big to hold in memory"))
        // that message comes only from the Content-Length check, made before the body is read
    }

    @Test fun aSmallBodyComesBackInMemory() {
        val body = bytes(5000)
        goodAnswer = { ok(body) }
        assertTrue(cdn(good).fetch("data", key)!!.contentEquals(body))
    }
}
