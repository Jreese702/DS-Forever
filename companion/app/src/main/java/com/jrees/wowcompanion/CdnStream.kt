package com.jrees.wowcompanion

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * CDN BODIES STREAMED, NEVER HELD WHOLE (issue #70, 2026-11-11: a game update died with "Failed to allocate a 68008824 byte
 * allocation ... growth limit 536870912" at okhttp ResponseBody.bytes() <- WowClientDownloader$Cdn.fetch; the companion's archive
 * ranges were read whole too, six at a time). A body is copied in [BUF]-sized pieces to its sink (a temp file for anything big) while
 * its length, its MD5, its first [HEAD] bytes and its last [TAIL] bytes are counted on the way: that is everything the integrity
 * checks of 8838e9e9 need (206 + exact length, a config's MD5 name, an index footer's MD5, a BLTE magic), without the whole body.
 */
object CdnStream {
    /** a body above this goes to a temp file, never into one byte array */
    const val IN_MEMORY_MAX = 4L shl 20
    const val BUF = 64 * 1024
    const val HEAD = 4
    /** an archive index's footer (WowClientDownloader.indexOk) */
    const val TAIL = 28

    /** what the checks need of a body: its length, its MD5 (lower-case hex), its first and last bytes (fewer when it is shorter) */
    class Body(val size: Long, val md5: String, val head: ByteArray, val tail: ByteArray)

    /**
     * [input] to [out] in pieces, counted on the way. More than [limit] bytes is an IOException (a body longer than it said, or
     * than any file of its kind): it is stopped there rather than read on.
     */
    fun copy(input: InputStream, out: OutputStream, limit: Long = Long.MAX_VALUE): Body {
        val digest = MessageDigest.getInstance("MD5")
        val buf = ByteArray(BUF)
        val head = ByteArray(HEAD)
        val tail = ByteArray(TAIL)
        var headN = 0
        var tailN = 0
        var size = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            size += n
            if (size > limit) throw IOException("a body longer than $limit B")
            digest.update(buf, 0, n)
            out.write(buf, 0, n)
            if (headN < HEAD) { val k = minOf(HEAD - headN, n); System.arraycopy(buf, 0, head, headN, k); headN += k }
            // the last TAIL bytes: a long piece replaces them, a short one shifts the kept ones left
            if (n >= TAIL) { System.arraycopy(buf, n - TAIL, tail, 0, TAIL); tailN = TAIL } else {
                val keep = minOf(tailN, TAIL - n)
                System.arraycopy(tail, tailN - keep, tail, 0, keep)
                System.arraycopy(buf, 0, tail, keep, n)
                tailN = keep + n
            }
        }
        val md5 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return Body(size, md5, head.copyOf(headN), tail.copyOf(tailN))
    }

    /** reads at most [left] bytes of [input] (one BLTE chunk of a stream); close() leaves [input] open */
    class Limited(private val input: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int = if (left <= 0) -1 else input.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
        /** the rest of this piece read and dropped; a stream that ends first is cut short */
        fun drain() {
            val buf = ByteArray(8192)
            while (left > 0) if (read(buf) < 0) throw IOException("cut short ($left B missing)")
        }
        override fun close() {}
    }
}
