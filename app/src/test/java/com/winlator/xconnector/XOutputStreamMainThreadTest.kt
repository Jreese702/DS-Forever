package com.winlator.xconnector

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #481 (1.0.33 ANR, "Input dispatching timed out"): the main thread blocked 2.5 .. 11.5 s in ClientSocket.write, under
 * XOutputStream.flush, sending a pointer-motion event to a client that was not reading. A main-thread flush must never wait on the
 * socket; the bytes must still reach the client whole and in flush order.
 */
class XOutputStreamMainThreadTest {
    /** a socket whose writes block until [open] counts down (a client that is not reading) */
    private class StalledSink : OutputQueue.Sink {
        val open = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val got = ByteArrayOutputStream()
        val fds = ArrayList<Int>()
        override fun write(data: ByteBuffer) {
            entered.countDown()
            open.await(10, TimeUnit.SECONDS)
            take(data)
        }
        override fun sendAncillaryMsg(data: ByteBuffer, ancillaryFd: Int) { synchronized(this) { fds += ancillaryFd }; take(data) }
        private fun take(data: ByteBuffer) = synchronized(this) {
            val b = ByteArray(data.remaining()); data.get(b); got.write(b)
        }
        fun bytes(): ByteArray = synchronized(this) { got.toByteArray() }
    }

    private val drains = Executors.newSingleThreadExecutor()
    @After fun stop() { drains.shutdownNow() }

    @Volatile private var onMain = true
    private fun stream(sink: OutputQueue.Sink, cap: Int = OutputQueue.MAX_QUEUED_BYTES) =
        XOutputStream(sink, { onMain }, drains, cap, 64)

    private fun XOutputStream.send(vararg b: Int) { lock().use { b.forEach { v -> writeByte(v.toByte()) } } }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!cond()) { assertTrue(what, System.nanoTime() < until); Thread.sleep(5) }
    }

    @Test fun aMainThreadFlushDoesNotWaitForAStalledClient() {
        val sink = StalledSink()
        val out = stream(sink)
        val t0 = System.nanoTime()
        out.send(1, 2, 3)                                       // before the fix: blocked here until the client read
        out.send(4, 5)
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        assertTrue("main-thread flush took $ms ms", ms < 1_000)
        sink.open.countDown()
        waitFor("the queued bytes reach the client") { sink.bytes().size == 5 }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), sink.bytes())
    }

    @Test fun anotherThreadStillWritesDirectlyWhileTheQueueIsIdle() {
        val sink = StalledSink()
        sink.open.countDown()
        val out = stream(sink)
        onMain = false
        out.send(9, 8)                                          // the X server's own thread: written before the flush returns,
        assertArrayEquals(byteArrayOf(9, 8), sink.bytes())      // on that thread, as before (no hand-off, no copy)
    }

    @Test fun aServerFlushBehindTheQueueKeepsItsPlace() {
        val sink = StalledSink()
        val out = stream(sink)
        out.send(1)                                             // main: queued, the drain blocks on the socket
        assertTrue(sink.entered.await(5, TimeUnit.SECONDS))
        onMain = false
        out.send(2)                                             // the server thread while the queue drains: after it, never before
        sink.open.countDown()
        waitFor("both") { sink.bytes().size == 2 }
        assertArrayEquals(byteArrayOf(1, 2), sink.bytes())
    }

    @Test fun pastTheCapMainThreadChunksAreDroppedWhole() {
        val sink = StalledSink()
        val out = stream(sink, cap = 4)
        out.send(1, 2, 3)                                       // taken by the drain (blocks)
        assertTrue(sink.entered.await(5, TimeUnit.SECONDS))
        out.send(4, 5, 6)                                       // queued (3 of 4)
        out.send(7, 8)                                          // over the cap: dropped whole, never split
        sink.open.countDown()
        waitFor("the kept chunks") { sink.bytes().size == 6 }
        Thread.sleep(50)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), sink.bytes())
    }

    @Test fun anAncillaryMessageWaitsForTheQueueAndGoesDirect() {
        val sink = StalledSink()
        val out = stream(sink)
        out.send(1)                                             // main: queued, blocked
        assertTrue(sink.entered.await(5, TimeUnit.SECONDS))
        onMain = false
        val server = Thread { out.lock().use { out.writeByte(2); out.setAncillaryFd(42) } }
        server.start()
        Thread.sleep(50)
        assertEquals(0, synchronized(sink) { sink.fds.size })   // still waiting behind the queue: order kept
        sink.open.countDown()
        server.join(5_000)
        assertArrayEquals(byteArrayOf(1, 2), sink.bytes())
        assertEquals(listOf(42), synchronized(sink) { sink.fds.toList() })
    }
}
