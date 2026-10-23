package com.jrees.wowcompanion.stream

import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Collections
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * NvHttp against fake GameStream servers on 127.0.0.1 (stream2, 2026-10-23: every stream start ended in a bare "Read timed out"
 * after serverinfo). A real TLS server that demands a client certificate, a server that takes the TCP connection and never answers
 * the TLS handshake (what the bundled Sunshine's 52984 did), a wrong certificate, a refused port. Off-device.
 */
class NvHttpTest {
    companion object {
        lateinit var client: ClientIdentity
        lateinit var server: ClientIdentity
        lateinit var other: ClientIdentity
        private val open = Collections.synchronizedList(ArrayList<ServerSocket>())
        @BeforeClass @JvmStatic fun keys() {
            client = ClientIdentity.generate(SecureRandom())
            server = ClientIdentity.generate(SecureRandom(), commonName = "Sunshine Gamestream Host")
            other = ClientIdentity.generate(SecureRandom(), commonName = "Someone Else")
        }
        @AfterClass @JvmStatic fun close() { for (s in open) try { s.close() } catch (_: Throwable) {} }
    }

    /** a Sunshine-like https server: client certificate REQUIRED; answers each request on its own connection, then closes */
    private class FakeHttps(val id: ClientIdentity, val body: String = "<root status_code=\"200\"><PairStatus>1</PairStatus></root>",
                            val code: Int = 200) {
        val clientCerts = Collections.synchronizedList(ArrayList<X509Certificate>())
        val requests = Collections.synchronizedList(ArrayList<String>())
        val sock: SSLServerSocket
        init {
            val ks = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry("s", id.key, CharArray(0), arrayOf(id.cert)) }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) { clientCerts.add(chain[0]) }
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(trustAll), SecureRandom()) }
            sock = ctx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket
            sock.needClientAuth = true
            open.add(sock)
            Thread {
                while (!sock.isClosed) {
                    val c = try { sock.accept() as SSLSocket } catch (_: Throwable) { break }
                    Thread { serve(c) }.apply { isDaemon = true; start() }
                }
            }.apply { isDaemon = true; start() }
        }
        val port get() = sock.localPort
        private fun serve(c: Socket) = c.use {
            val head = StringBuilder()
            val ins = c.getInputStream()
            while (!head.endsWith("\r\n\r\n")) { val b = ins.read(); if (b < 0) return; head.append(b.toChar()) }
            requests.add(head.toString())
            val bytes = body.toByteArray()
            c.getOutputStream().write(("HTTP/1.1 $code OK\r\nContent-Length: ${bytes.size}\r\n\r\n").toByteArray() + bytes)
            c.getOutputStream().flush()
        }
    }

    private fun http(port: Int, pinned: X509Certificate?, log: MutableList<String>, tlsMs: Int = 1_500) =
        NvHttp("127.0.0.1", httpPort = port, httpsPort = port, identity = client, serverCert = pinned, log = { log.add(it) }, tlsMs = tlsMs)

    @Test fun httpsPresentsOurCertificateAndLogsOneLine() {
        val pc = FakeHttps(server)
        val log = ArrayList<String>()
        val body = http(pc.port, server.cert, log).serverInfo(true)
        assertEquals("1", XmlFields.get(body, "PairStatus"))
        assertArrayEquals(client.cert.encoded, pc.clientCerts.single().encoded)
        val line = log.single()
        assertTrue(line, line.startsWith("nvhttp GET https://127.0.0.1:${pc.port}/serverinfo -> HTTP 200"))
        assertTrue(line, line.endsWith(" ms)"))
        assertFalse("the query (input key, pairing material) is never logged", line.contains("uniqueid") || line.contains("?"))
        // one request per connection, closed after the answer (no keep-alive pool)
        assertTrue(pc.requests.single().contains("Connection: close"))
        assertTrue(pc.requests.single().startsWith("GET /serverinfo?uniqueid=0123456789ABCDEF&uuid="))
    }

    @Test fun twoRequestsAreTwoConnections() {
        val pc = FakeHttps(server)
        val h = http(pc.port, server.cert, ArrayList())
        h.serverInfo(true); h.appList()
        assertEquals(2, pc.clientCerts.size)                      // two handshakes: nothing kept open between them
        assertEquals(2, pc.requests.size)
    }

    @Test fun aServerThatNeverAnswersTheHandshakeIsATlsStageTimeout() {
        // takes the TCP connection, reads the ClientHello, says nothing (the bundled Sunshine's 52984 on 2026-10-23)
        val mute = ServerSocket(0, 5, InetAddress.getLoopbackAddress()).also { open.add(it) }
        Thread { while (!mute.isClosed) try { val c = mute.accept(); Thread { try { c.getInputStream().readBytes() } catch (_: Throwable) {} }.start() } catch (_: Throwable) { break } }
            .apply { isDaemon = true; start() }
        val log = ArrayList<String>()
        val t0 = System.currentTimeMillis()
        try { http(mute.localPort, server.cert, log, tlsMs = 800).serverInfo(true); fail("no answer expected") }
        catch (e: NvHttpStageException) {
            assertEquals("tls", e.stage); assertTrue(e.timedOut); assertEquals(mute.localPort, e.port)
            assertTrue(System.currentTimeMillis() - t0 < 3_000)
            val text = StreamDiag.tlsText("DEN", e)
            assertTrue(text, text.contains("port (${mute.localPort})") && text.contains("restart DS Forever Desktop"))
        }
        assertTrue(log.single(), log.single().contains("https://127.0.0.1:${mute.localPort}/serverinfo -> tls failed: SocketTimeoutException"))
    }

    @Test fun aDifferentCertificateIsAPinMismatch() {
        val pc = FakeHttps(other)
        try { http(pc.port, server.cert, ArrayList()).serverInfo(true); fail("pinned") }
        catch (e: NvHttpStageException) {
            assertEquals("tls", e.stage)
            assertTrue(StreamDiag.isPinMismatch(e))
            assertTrue(StreamDiag.tlsText("DEN", e).contains("pair again"))
        }
    }

    @Test fun aRefusedPortIsTheConnectStage() {
        val free = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).let { val p = it.localPort; it.close(); p }
        try { http(free, server.cert, ArrayList()).serverInfo(false); fail("refused") }
        catch (e: NvHttpStageException) {
            assertEquals("connect", e.stage)
            assertTrue(StreamDiag.stageText("DEN", e).startsWith("DEN didn't answer on port $free"))
        }
    }

    @Test fun statusCodesBecomeHostHttpExceptions() {
        val unauthorized = FakeHttps(server, "<root status_code=\"401\" status_message=\"The client is not authorized. Certificate verification failed.\"/>")
        try { http(unauthorized.port, server.cert, ArrayList()).serverInfo(true); fail("401") }
        catch (e: HostHttpException) { assertEquals(401, e.code); assertTrue(e.message!!.contains("not authorized")) }
        val missing = FakeHttps(server, "nope", code = 404)
        try { http(missing.port, server.cert, ArrayList()).appList(); fail("404") } catch (e: HostHttpException) { assertEquals(404, e.code) }
    }

    @Test fun answersByLengthChunkedOrClose() {
        fun read(s: String) = HttpOnce.read(ByteArrayInputStream(s.toByteArray()))
        assertEquals("hello", read("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhelloEXTRA").body)
        assertEquals("hello world", read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n").body)
        val r = read("HTTP/1.0 404 Not Found\r\nServer: x\r\n\r\nall of it")
        assertEquals(404, r.code); assertEquals("Not Found", r.reason); assertEquals("all of it", r.body)
    }
}
