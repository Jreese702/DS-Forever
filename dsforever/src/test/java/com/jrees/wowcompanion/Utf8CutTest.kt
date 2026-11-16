package com.jrees.wowcompanion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** issue #358 ("Some Cyrillic characters are replaced with question marks"): a log read that ends inside a letter keeps its first bytes */
class Utf8CutTest {
    private val ru = "Сяэ Анна Нина Эхо Ёлка. "

    @Test fun anEndingInsideALetterIsKept() {
        val b = "ab".toByteArray() + "я".toByteArray()             // D1 8F
        assertEquals(0, Utf8Cut.tail(b, b.size))                    // whole
        assertEquals(1, Utf8Cut.tail(b, b.size - 1))                // D1 alone: kept
        val cjk = "龍".toByteArray()                                // 3 bytes
        assertEquals(1, Utf8Cut.tail(cjk, 1)); assertEquals(2, Utf8Cut.tail(cjk, 2)); assertEquals(0, Utf8Cut.tail(cjk, 3))
        val emoji = "x😀".toByteArray()                             // 1 + 4 bytes
        assertEquals(3, Utf8Cut.tail(emoji, 4)); assertEquals(0, Utf8Cut.tail(emoji, 5))
        assertEquals(0, Utf8Cut.tail("plain".toByteArray(), 5))
        assertEquals(0, Utf8Cut.tail(ByteArray(0), 0))
        // a broken byte run is not held back (it decodes as before)
        assertEquals(0, Utf8Cut.tail(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()), 4))
        assertEquals(0, Utf8Cut.tail(byteArrayOf(0xFF.toByte()), 1))
    }

    @Test fun reads_cutAnywhere_decodeToTheSameText() {
        val text = (ru.repeat(40) + "María Íñigo 李小龍 😀\n").repeat(3)
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (step in listOf(1, 2, 3, 5, 7, 64, 1000)) {
            var carry = Utf8Cut.EMPTY
            val out = StringBuilder()
            var at = 0
            while (at < bytes.size) {
                val n = minOf(step, bytes.size - at)
                val (s, keep) = Utf8Cut.join(carry, bytes.copyOfRange(at, at + n), n)
                out.append(s); carry = keep; at += n
            }
            assertEquals("reads of $step bytes", text, out.toString())
            assertArrayEquals(Utf8Cut.EMPTY, carry)
            assertEquals(false, out.contains('�'))
        }
    }

    @Test fun theOldDecodeBrokeTheSameReads() {
        // 1.0.32: each read decoded on its own - a read ending inside "я" gave two U+FFFD
        val b = "Ая".toByteArray()
        val old = String(b, 0, 3, Charsets.UTF_8) + String(b, 3, 1, Charsets.UTF_8)
        assertEquals(true, old.contains('�'))
        val (s1, k1) = Utf8Cut.join(Utf8Cut.EMPTY, b.copyOfRange(0, 3), 3)
        val (s2, k2) = Utf8Cut.join(k1, b.copyOfRange(3, 4), 1)
        assertEquals("Ая", s1 + s2); assertEquals(0, k2.size)
    }
}
