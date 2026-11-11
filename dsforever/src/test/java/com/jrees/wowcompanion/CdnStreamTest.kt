package com.jrees.wowcompanion

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Issue #70 (2026-11-11, Java heap OOM on a game update): CDN bodies are streamed and checked on the way, never held whole. */
class CdnStreamTest {
    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** [data] handed out a few bytes at a time (a slow link: the pieces never line up with the buffer) */
    private class Trickle(private val data: ByteArray, private val step: Int) : InputStream() {
        private var at = 0
        override fun read(): Int = if (at < data.size) data[at++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at >= data.size) return -1
            val n = minOf(len, step, data.size - at)
            System.arraycopy(data, at, b, off, n); at += n
            return n
        }
    }

    @Test fun aBodyIsCountedOnTheWay() {
        val data = ByteArray(300_000) { (it * 31 + 7).toByte() }
        for (step in listOf(1, 3, 27, 28, 29, 1000, 70_000, Int.MAX_VALUE)) {
            val out = ByteArrayOutputStream()
            val body = CdnStream.copy(Trickle(data, step), out)
            assertArrayEquals("step $step", data, out.toByteArray())
            assertEquals(data.size.toLong(), body.size)
            assertEquals(md5(data), body.md5)
            assertArrayEquals(data.copyOf(4), body.head)
            assertArrayEquals(data.copyOfRange(data.size - 28, data.size), body.tail)
        }
    }

    @Test fun aShortBodyKeepsWhatItHas() {
        val body = CdnStream.copy(Trickle("BLT".toByteArray(), 1), ByteArrayOutputStream())
        assertEquals(3L, body.size)
        assertArrayEquals("BLT".toByteArray(), body.head)
        assertArrayEquals("BLT".toByteArray(), body.tail)
        val empty = CdnStream.copy(ByteArray(0).inputStream(), ByteArrayOutputStream())
        assertEquals(0L, empty.size); assertEquals(md5(ByteArray(0)), empty.md5); assertEquals(0, empty.tail.size)
    }

    @Test fun aBodyLongerThanItsLimitIsStopped() {
        val out = ByteArrayOutputStream()
        try { CdnStream.copy(Trickle(ByteArray(10_000), 4096), out, 5_000); fail("read past the limit") } catch (_: IOException) {}
        assertTrue(out.size() <= 5_000)                                            // nothing past the limit was written
        assertEquals(5_000L, CdnStream.copy(ByteArray(5_000).inputStream(), ByteArrayOutputStream(), 5_000).size)
    }

    @Test fun aLimitedPieceStopsAtItsEndAndLeavesTheRest() {
        val input = "abcdefghij".toByteArray().inputStream()
        val piece = CdnStream.Limited(input, 4)
        assertEquals("ab", String(ByteArray(2).also { piece.read(it) }))
        piece.drain()
        assertEquals(-1, piece.read())
        assertEquals('e'.code, input.read())                                     // the next piece starts where this one ended
        try { CdnStream.Limited("xy".toByteArray().inputStream(), 5).drain(); fail("a cut piece drained") } catch (_: IOException) {}
    }

    @Test fun anArchiveRangeIsExactAndBlte() {
        val ok = "BLTE".toByteArray() + ByteArray(96)
        assertNull(CdnArchives.rangeProblem(CdnStream.copy(ok.inputStream(), ByteArrayOutputStream()), 100))
        assertTrue(CdnArchives.rangeProblem(CdnStream.copy(ok.copyOf(99).inputStream(), ByteArrayOutputStream()), 100) != null)
        val portal = "<html>".toByteArray() + ByteArray(94)
        assertTrue(CdnArchives.rangeProblem(CdnStream.copy(portal.inputStream(), ByteArrayOutputStream()), 100) != null)
    }
}
