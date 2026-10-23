package com.jrees.wowcompanion.stream

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/** an HTTP answer that was not 200, or a GameStream status_code that was not */
class HostHttpException(val code: Int, message: String) : IOException(message)

/**
 * A request that failed at one [stage]: "connect" (no TCP), "tls" (connected, but the TLS handshake failed or never answered),
 * "request" (sending), "response" (waiting for / reading the answer). The stage is what tells "the PC is off" from "the PC's
 * secure port is stuck" (stream2, 2026-10-23: the bundled Sunshine took the TCP connection on 52984 and never answered the
 * ClientHello; Moonlight's error was a bare "Read timed out").
 */
class NvHttpStageException(val stage: String, val port: Int, val elapsedMs: Long, cause: Throwable) :
    IOException("$stage on port $port after $elapsedMs ms: ${cause.javaClass.simpleName}: ${cause.message}", cause) {
    val timedOut: Boolean get() = cause is SocketTimeoutException
}

/**
 * ONE HTTP/1.1 GET over a fresh socket, closed after the answer ("Connection: close"). No pool: moonlight-android runs its OkHttp
 * with a connection pool of 0 for GameStream hosts, and a kept-alive TLS connection is the one thing a Sunshine https server
 * might hold open for us. Each stage has its own time-out and its own error ([NvHttpStageException]).
 */
object HttpOnce {
    data class Answer(val code: Int, val reason: String, val body: String)

    /** [readMs] 0 = wait as long as it takes for the answer (launch, the PIN wait); the TLS handshake is still capped at [tlsMs] */
    fun get(tls: SSLSocketFactory?, host: String, port: Int, target: String, connectMs: Int, tlsMs: Int, readMs: Int): Answer {
        val t0 = System.currentTimeMillis()
        fun fail(stage: String, t: Throwable): Nothing = throw NvHttpStageException(stage, port, System.currentTimeMillis() - t0, t)
        val raw = Socket()
        try {
            try { raw.connect(InetSocketAddress(host, port), connectMs); raw.tcpNoDelay = true } catch (t: Throwable) { fail("connect", t) }
            var s: Socket = raw
            if (tls != null) {
                try {
                    val ssl = tls.createSocket(raw, host, port, true) as SSLSocket
                    ssl.soTimeout = tlsMs
                    ssl.startHandshake()
                    s = ssl
                } catch (t: Throwable) { fail("tls", t) }
            }
            try {
                s.soTimeout = readMs
                val req = "GET $target HTTP/1.1\r\nHost: $host:$port\r\nConnection: close\r\nUser-Agent: DSForever-Thor\r\nAccept: */*\r\n\r\n"
                s.getOutputStream().apply { write(req.toByteArray(Charsets.US_ASCII)); flush() }
            } catch (t: Throwable) { fail("request", t) }
            try { return read(s.getInputStream()) } catch (t: Throwable) { fail("response", t) }
        } finally { try { raw.close() } catch (_: Throwable) {} }
    }

    private fun line(input: InputStream): String {
        val b = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) { if (b.isEmpty()) throw EOFException("closed before the answer"); break }
            if (c == '\n'.code) break
            if (c != '\r'.code) b.append(c.toChar())
            if (b.length > 16_384) throw IOException("header line too long")
        }
        return b.toString()
    }

    /** status line, headers, then the body by Content-Length, chunked, or up to the close */
    fun read(input0: InputStream): Answer {
        val input = input0.buffered()
        val status = line(input)
        val parts = status.split(' ', limit = 3)
        val code = parts.getOrNull(1)?.toIntOrNull() ?: throw IOException("not HTTP: ${status.take(40)}")
        var length = -1L; var chunked = false
        while (true) {
            val h = line(input)
            if (h.isEmpty()) break
            val k = h.substringBefore(':').trim().lowercase(); val v = h.substringAfter(':', "").trim()
            if (k == "content-length") length = v.toLongOrNull() ?: -1
            if (k == "transfer-encoding" && v.lowercase().contains("chunked")) chunked = true
        }
        val out = ByteArrayOutputStream()
        fun copy(n: Long) {
            val buf = ByteArray(8192); var left = n
            while (left > 0) { val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) throw EOFException("body cut short"); out.write(buf, 0, r); left -= r }
        }
        when {
            chunked -> while (true) { val n = line(input).substringBefore(';').trim().toLong(16); if (n == 0L) break; copy(n); line(input) }
            length >= 0 -> copy(length)
            else -> input.copyTo(out)
        }
        if (out.size() > 4 * 1024 * 1024) throw IOException("answer too large")
        return Answer(code, parts.getOrNull(2).orEmpty(), out.toString("UTF-8"))
    }
}

/**
 * GameStream's HTTP API on the PC (Sunshine's http port, e.g. 47989 / Desktop's 52989; https = http - 5), as moonlight-android's
 * NvHTTP.java uses it: serverinfo, pair, applist, launch / resume, cancel. https presents the app's client certificate
 * ([ClientIdentity]) and accepts ONLY the PC's certificate from the pairing (pinned; no CA, no host name check: the pin is the
 * identity). Every request leaves ONE log line ([log]): the URL with its port (never the query: it carries the input key and the
 * pairing material), the status or the failed stage with the exception, and the time it took.
 */
class NvHttp(
    val address: String,
    val httpPort: Int = 47989,
    var httpsPort: Int = 47984,
    private val identity: ClientIdentity,
    @Volatile var serverCert: X509Certificate? = null,
    private val deviceName: String = "DSForever",
    private val log: (String) -> Unit = ::defaultLog,
    /** the TLS handshake's own time-out (the tests shorten it) */
    private val tlsMs: Int = TLS_MS,
) : PairHttp {

    /** one GET; [readMs] = 0 waits as long as it takes for the answer (the PIN wait, launch) */
    fun get(https: Boolean, path: String, query: String? = null, readMs: Int = READ_MS): String {
        val q = buildString {
            append("uniqueid=").append(UNIQUE_ID).append("&uuid=").append(UUID.randomUUID())
            if (!query.isNullOrEmpty()) append('&').append(query)
        }
        val port = if (https) httpsPort else httpPort
        val shown = "${if (https) "https" else "http"}://$address:$port/$path"
        val t0 = System.currentTimeMillis()
        val a = try {
            HttpOnce.get(if (https) sslContext().socketFactory else null, address, port, "/$path?$q", CONNECT_MS, tlsMs, readMs)
        } catch (e: NvHttpStageException) {
            log("nvhttp GET $shown -> ${e.stage} failed: ${e.cause?.javaClass?.simpleName}: ${e.cause?.message} (${e.elapsedMs} ms)")
            throw e
        }
        val ms = System.currentTimeMillis() - t0
        val sc = XmlFields.statusCode(a.body)
        log("nvhttp GET $shown -> HTTP ${a.code}${if (sc != null && sc != 200) " status_code $sc" else ""} (${a.body.length} b, $ms ms)")
        if (a.code != 200) throw HostHttpException(a.code, "$path: HTTP ${a.code} ${a.reason}".trim())
        if (sc != null && sc != 200) throw HostHttpException(sc, "$path: ${XmlFields.statusMessage(a.body) ?: "status $sc"}")
        return a.body
    }

    fun serverInfo(https: Boolean): String = get(https, "serverinfo", readMs = READ_MS)
    fun appList(): String = get(true, "applist")
    fun cancel(): String = get(true, "cancel", readMs = 0)

    /** launch or resume ([verb]) with the stream's parameters; the answer holds sessionUrl0 (Sunshine may run the app's prep first) */
    fun launch(verb: String, query: String): String = get(true, verb, query, readMs = LAUNCH_MS)

    override fun pairCommand(args: String, waitForUser: Boolean): String =
        get(false, "pair", "devicename=$deviceName&updateState=1&$args", readMs = if (waitForUser) 0 else READ_MS)

    override fun pairChallenge(serverCert: X509Certificate): String {
        this.serverCert = serverCert
        return get(true, "pair", "devicename=$deviceName&updateState=1&phrase=pairchallenge")
    }

    override fun unpair() { get(false, "unpair") }

    internal fun sslContext(): SSLContext {
        val pinned = serverCert
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("server only")
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
                if (pinned == null || !chain[0].encoded.contentEquals(pinned.encoded)) throw CertificateException("not the paired PC's certificate")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val keys = object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(identity.cert)
            override fun getPrivateKey(alias: String?): PrivateKey = identity.key
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(keys), arrayOf(trust), SecureRandom()) }
    }

    companion object {
        /** every Moonlight client uses this one id (so a session started by one can be resumed / quit by another) */
        const val UNIQUE_ID = "0123456789ABCDEF"
        const val CONNECT_MS = 3_000
        /** the TLS handshake: a few round trips on a LAN, so anything near this is a stuck server, not a slow one */
        const val TLS_MS = 5_000
        const val READ_MS = 7_000
        /** launch / resume: Sunshine runs the app's prep commands before it answers */
        const val LAUNCH_MS = 60_000
        private const val ALIAS = "dsf"

        fun defaultLog(line: String) { try { android.util.Log.i("DSFSTREAM", line) } catch (_: Throwable) { println(line) } }
    }
}
