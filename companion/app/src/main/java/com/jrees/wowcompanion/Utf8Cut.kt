package com.jrees.wowcompanion

/**
 * A READ THAT ENDS INSIDE A LETTER (issue #358, 2026-11-15: "Some Cyrillic characters are replaced with question marks"): the game's
 * log arrives in blocks cut at any byte (General.log is block-buffered at ~48 KiB, and a read takes at most LogTail.MAX_READ), so the
 * last bytes of a read can be the first half of a two-, three- or four-byte UTF-8 letter. Decoded on their own, both halves became
 * U+FFFD. [tail] says how many bytes at the end of a read start a letter the read cut short; the reader keeps them for the next read.
 * Pure (the tests).
 */
object Utf8Cut {
    /** how many of the last bytes of buf[0, n) are an incomplete UTF-8 letter (0 - 3); a broken or complete ending is 0 */
    fun tail(buf: ByteArray, n: Int): Int {
        if (n <= 0) return 0
        var i = n - 1
        var cont = 0
        while (i >= 0 && cont < 3 && (buf[i].toInt() and 0xC0) == 0x80) { i--; cont++ }
        if (i < 0) return 0
        val lead = buf[i].toInt() and 0xFF
        val need = when {
            (lead and 0xE0) == 0xC0 -> 2
            (lead and 0xF0) == 0xE0 -> 3
            (lead and 0xF8) == 0xF0 -> 4
            else -> return 0                                      // ASCII, or a byte no letter starts with: nothing to keep
        }
        val have = n - i
        return if (have < need) have else 0
    }

    /** [carry] (the bytes the last read kept) then buf[0, n), as text, and the bytes this read keeps for the next one */
    fun join(carry: ByteArray, buf: ByteArray, n: Int): Pair<String, ByteArray> {
        val all = if (carry.isEmpty()) buf else ByteArray(carry.size + n).also {
            System.arraycopy(carry, 0, it, 0, carry.size); System.arraycopy(buf, 0, it, carry.size, n)
        }
        val len = if (carry.isEmpty()) n else carry.size + n
        val keep = tail(all, len)
        return String(all, 0, len - keep, Charsets.UTF_8) to (if (keep == 0) EMPTY else all.copyOfRange(len - keep, len))
    }

    val EMPTY = ByteArray(0)
}
