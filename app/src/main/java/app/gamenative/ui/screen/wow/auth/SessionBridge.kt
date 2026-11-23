package app.gamenative.ui.screen.wow.auth

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom

/** A private launch capability authenticates both ends before any credential crosses loopback. */
internal class SessionBridge : Closeable {
    private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply {
        soTimeout = 180_000
    }
    private val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val config: String get() = "${listener.localPort}\n${nonce.joinToString("") { "%02x".format(it) }}\n"
    @Volatile private var candidate: Socket? = null
    @Volatile private var closed = false

    fun accept(): Peer {
        // Unauthenticated peers cannot hold the listener indefinitely or receive the nonce.
        repeat(16) {
            val socket = listener.accept()
            candidate = socket
            if (closed) { socket.close(); throw IOException("Bridge closed") }
            socket.soTimeout = 2_000
            try {
                val input = DataInputStream(socket.getInputStream())
                val received = ByteArray(32)
                input.readFully(received)
                val accepted = MessageDigest.isEqual(proof("CLIENT"), received)
                received.fill(0)
                if (!accepted) throw IOException("Bridge authentication failed")
                socket.getOutputStream().apply { write(proof("SERVER")); flush() }
                nonce.fill(0)
                listener.close()
                socket.soTimeout = 30_000
                return Peer(socket)
            } catch (error: IOException) {
                socket.close()
                if (listener.isClosed) throw error
            }
        }
        throw IOException("Too many unauthenticated bridge connections")
    }

    override fun close() {
        closed = true
        listener.close()
        candidate?.close()
        nonce.fill(0)
    }

    private fun proof(role: String): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest("WOW-SESSION-$role-1".toByteArray(Charsets.US_ASCII) + nonce)

    class Peer(private val socket: Socket) : Closeable {
        private val input = DataInputStream(socket.getInputStream())
        private val output = DataOutputStream(socket.getOutputStream())

        fun device(): ByteArray = readFrame(4096)

        /**
         * The credential, then (DS Forever, the user's ruling 2026-11-23) the game-account frame: [NO_ACCOUNT] ("-", the game asks) or
         * "<REGION>:<name>" (GameAccountHandover.frame; the helper writes GAME_ACCOUNT and REGION beside the credential).
         */
        fun launch(token: ByteArray, account: ByteArray = NO_ACCOUNT) {
            require(token.size in 1..2048 && token.all { (it.toInt() and 255) in 32..126 }) {
                "Invalid game credential"
            }
            // an unusable frame would only be read as none by the helper: never sent, the game asks instead
            val frame = account.takeIf { it.size in 1..ACCOUNT_FRAME_LIMIT && it.all { b -> (b.toInt() and 255) in 32..126 } } ?: NO_ACCOUNT
            output.writeInt(token.size)
            output.write(token)
            output.writeInt(frame.size)
            output.write(frame)
            output.flush()
            val status = readFrame(128).toString(Charsets.US_ASCII)
            if (status != "STARTED") {
                // Do not echo a peer-provided payload into a log or dialog.
                throw IOException("Wine could not hand the session to the game")
            }
        }

        private fun readFrame(limit: Int): ByteArray {
            val length = input.readInt()
            if (length !in 1..limit) throw IOException("Invalid bridge frame length")
            return ByteArray(length).also(input::readFully)
        }

        override fun close() = socket.close()

        companion object {
            /** no game account handed over: the helper removes GAME_ACCOUNT and REGION, and the game shows its list */
            val NO_ACCOUNT: ByteArray get() = byteArrayOf('-'.code.toByte())
            /** the helper's BRIDGE_ACCOUNT_FRAME_LIMIT: "XX:" and a 64-character name */
            const val ACCOUNT_FRAME_LIMIT = 67
        }
    }
}
