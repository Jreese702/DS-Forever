package com.jrees.wowcompanion.stream

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** one connection to the bridge (a real WebSocket on the device, a script in the tests) */
interface BridgeConn {
    fun send(text: String)
    /** the next text message; null = the bridge closed ([closeCode] says why) */
    fun receive(): String?
    val closeCode: Int
    /** the SHA-256 (DER, lower-case hex) of the certificate the bridge showed */
    val peerFingerprint: String
    fun close(code: Int = 1000)
}

/**
 * opens [BridgeConn]s: ws:// when [tls] is false (Desktop's bridge v1), wss:// when true, with [pin] = the bridge certificate's
 * fingerprint to insist on (null = trust what is shown: pairing only)
 */
fun interface BridgeDialer {
    fun dial(address: String, port: Int, tls: Boolean, pin: String?): BridgeConn
}

/** RFC 6455 framing, the client's half (frames out are masked; frames in are not) */
object WsCodec {
    const val TEXT = 0x1; const val CONT = 0x0; const val CLOSE = 0x8; const val PING = 0x9; const val PONG = 0xA
    const val MAX_MESSAGE = 2 * 1024 * 1024
    const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    data class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    fun encode(opcode: Int, payload: ByteArray, mask: ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x80 or opcode)
        val m = if (mask != null) 0x80 else 0
        when {
            payload.size < 126 -> out.write(m or payload.size)
            payload.size < 65536 -> { out.write(m or 126); out.write(payload.size ushr 8); out.write(payload.size and 0xFF) }
            else -> { out.write(m or 127); for (i in 7 downTo 0) out.write(((payload.size.toLong() ushr (8 * i)) and 0xFF).toInt()) }
        }
        if (mask != null) {
            out.write(mask)
            out.write(ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i and 3].toInt()).toByte() })
        } else out.write(payload)
        return out.toByteArray()
    }

    /** one frame; [first] = its first byte when the caller already read it (-1 = read it here) */
    fun read(input: InputStream, first: Int = -1): Frame {
        val d = DataInputStream(input)
        val b0 = if (first >= 0) first else d.readUnsignedByte()
        val b1 = d.readUnsignedByte()
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) len = d.readUnsignedShort().toLong() else if (len == 127L) len = d.readLong()
        if (len < 0 || len > MAX_MESSAGE) throw IOException("frame too large ($len)")
        val mask = if (b1 and 0x80 != 0) ByteArray(4).also { d.readFully(it) } else null
        val p = ByteArray(len.toInt()); d.readFully(p)
        if (mask != null) for (i in p.indices) p[i] = (p[i].toInt() xor mask[i and 3].toInt()).toByte()
        return Frame(b0 and 0x80 != 0, b0 and 0x0F, p)
    }

    fun accept(key: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray()))
}

/**
 * A WebSocket to the bridge (docs/STREAM_BRIDGE_PROTOCOL.md), no library: text frames, ping / pong, close. Plain ws:// as Desktop's
 * bridge v1 serves it, or wss:// where trust is a PIN, not a CA: the pairing takes the certificate shown (trust on first use: the
 * player just read the code off that PC) and every later connection insists on the same SHA-256. Keep-alive: a ping every [PING_MS]; [IDLE_MS] without any frame from
 * the bridge = lost.
 */
class WsClient private constructor(
    private val socket: Socket,
    override val peerFingerprint: String,
) : BridgeConn {
    private val input = socket.getInputStream().buffered()
    private val output: OutputStream = socket.getOutputStream()
    private val rng = SecureRandom()
    @Volatile override var closeCode = 0
        private set
    @Volatile private var lastSent = System.currentTimeMillis()
    private var lastHeard = System.currentTimeMillis()

    /** the rest of a frame: read time-outs are waited out, up to [FRAME_MS] for one frame */
    private val patient = object : InputStream() {
        override fun read(): Int {
            val until = System.currentTimeMillis() + FRAME_MS
            while (true) {
                try { return input.read() } catch (e: SocketTimeoutException) { if (System.currentTimeMillis() > until) throw IOException("a frame stalled") }
            }
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val until = System.currentTimeMillis() + FRAME_MS
            while (true) {
                try { return input.read(b, off, len) } catch (e: SocketTimeoutException) { if (System.currentTimeMillis() > until) throw IOException("a frame stalled") }
            }
        }
    }

    override fun send(text: String) = write(WsCodec.TEXT, text.toByteArray(Charsets.UTF_8))

    private fun write(op: Int, payload: ByteArray) {
        val mask = ByteArray(4).also { rng.nextBytes(it) }
        synchronized(output) { output.write(WsCodec.encode(op, payload, mask)); output.flush() }
        lastSent = System.currentTimeMillis()
    }

    override fun receive(): String? {
        val msg = ByteArrayOutputStream()
        var inText = false
        while (true) {
            // a quiet socket times out on the FIRST byte only (the keep-alive's tick); once a frame has begun, its rest is waited for
            val first = try { input.read() } catch (e: SocketTimeoutException) {
                val now = System.currentTimeMillis()
                if (now - lastHeard > IDLE_MS) throw IOException("the bridge went quiet")
                if (now - lastSent > PING_MS) write(WsCodec.PING, ByteArray(0))
                continue
            }
            if (first < 0) { if (closeCode == 0) closeCode = 1006; return null }
            val f = try { WsCodec.read(patient, first) } catch (e: EOFException) { if (closeCode == 0) closeCode = 1006; return null }
            lastHeard = System.currentTimeMillis()
            if (System.currentTimeMillis() - lastSent > PING_MS) write(WsCodec.PING, ByteArray(0))
            when (f.opcode) {
                WsCodec.PING -> write(WsCodec.PONG, f.payload)
                WsCodec.PONG -> {}
                WsCodec.CLOSE -> {
                    closeCode = if (f.payload.size >= 2) ((f.payload[0].toInt() and 0xFF) shl 8) or (f.payload[1].toInt() and 0xFF) else 1005
                    try { write(WsCodec.CLOSE, f.payload.copyOf(minOf(2, f.payload.size))) } catch (_: Throwable) {}
                    try { socket.close() } catch (_: Throwable) {}
                    return null
                }
                WsCodec.TEXT, WsCodec.CONT -> {
                    if (f.opcode == WsCodec.TEXT) { msg.reset(); inText = true }
                    if (!inText) continue                         // a binary message's continuation: not ours
                    msg.write(f.payload)
                    if (msg.size() > WsCodec.MAX_MESSAGE) throw IOException("message too large")
                    if (f.fin) return msg.toString("UTF-8")
                }
                else -> inText = false                            // binary: the protocol has none, ignored
            }
        }
    }

    override fun close(code: Int) {
        try { write(WsCodec.CLOSE, byteArrayOf((code ushr 8).toByte(), code.toByte())) } catch (_: Throwable) {}
        try { socket.close() } catch (_: Throwable) {}
        if (closeCode == 0) closeCode = code
    }

    companion object : BridgeDialer {
        const val CONNECT_MS = 4_000
        const val PING_MS = 15_000L
        const val IDLE_MS = 30_000L
        const val FRAME_MS = 10_000L

        override fun dial(address: String, port: Int, tls: Boolean, pin: String?): BridgeConn {
            if (!tls) {
                val raw = Socket()
                raw.connect(InetSocketAddress(address, port), CONNECT_MS)
                raw.tcpNoDelay = true
                raw.soTimeout = 1_000
                upgrade(raw, address, port)
                return WsClient(raw, "")
            }
            var seen = ""
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("server only")
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
                    val fp = Hex.fingerprint(chain[0].encoded)
                    seen = fp
                    if (pin != null && !fp.equals(pin, ignoreCase = true)) throw CertificateException("a different PC answered (certificate changed)")
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            val raw = Socket()
            raw.connect(InetSocketAddress(address, port), CONNECT_MS)
            val s = ctx.socketFactory.createSocket(raw, address, port, true) as SSLSocket
            s.soTimeout = 1_000
            s.startHandshake()
            upgrade(s, address, port)
            return WsClient(s, seen)
        }

        /** the HTTP upgrade to a WebSocket on [s] */
        private fun upgrade(s: Socket, address: String, port: Int) {
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
            val req = "GET ${BridgeProtocol.PATH} HTTP/1.1\r\nHost: $address:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nUser-Agent: DSForever-Thor\r\n\r\n"
            s.outputStream.write(req.toByteArray()); s.outputStream.flush()
            val head = readHead(s.inputStream)
            val status = head.lineSequence().firstOrNull().orEmpty()
            if (!status.contains(" 101")) { s.close(); throw IOException("the bridge refused the upgrade: $status") }
            val accept = head.lineSequence().firstOrNull { it.lowercase().startsWith("sec-websocket-accept:") }?.substringAfter(':')?.trim()
            if (accept != WsCodec.accept(key)) { s.close(); throw IOException("bad WebSocket accept") }
        }

        /** the HTTP head, byte by byte up to the blank line (so no frame byte is swallowed) */
        private fun readHead(input: InputStream): String {
            val b = StringBuilder()
            val deadline = System.currentTimeMillis() + CONNECT_MS
            while (!b.endsWith("\r\n\r\n")) {
                val c = try { input.read() } catch (e: SocketTimeoutException) {
                    if (System.currentTimeMillis() > deadline) throw IOException("no upgrade answer"); continue
                }
                if (c < 0) throw EOFException("closed during the upgrade")
                b.append(c.toChar())
                if (b.length > 8192) throw IOException("upgrade answer too long")
            }
            return b.toString()
        }
    }
}
