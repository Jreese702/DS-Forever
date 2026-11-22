package com.winlator.xserver

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #153 (1.0.37: 3 of 6 freeze reports had the UI thread parked in XServer.lock on a MotionEvent): every input to the X server goes
 * through one FIFO queue on its own thread. The sender never waits on the X lock, the order is kept, moves not started yet merge,
 * keys and buttons are never merged or dropped, and a slow op is told once. Measured: what the sender waits while another thread holds
 * the lock the input needs for 1.5 s (before: the whole 1.5 s on the sender's thread).
 */
class XInputQueueTest {
    private val log = Collections.synchronizedList(ArrayList<String>())
    private val xLock = ReentrantLock()
    private val mover = object : XInputQueue.Mover {
        override fun moveTo(x: Int, y: Int) { xLock.lock(); try { log += "to $x,$y" } finally { xLock.unlock() } }
        override fun moveBy(dx: Int, dy: Int) { xLock.lock(); try { log += "by $dx,$dy" } finally { xLock.unlock() } }
    }
    private fun op(name: String) = Runnable { xLock.lock(); try { log += name } finally { xLock.unlock() } }

    @Test fun theSenderNeverWaitsOnAHeldLock() {
        val q = XInputQueue("XInputTest", mover)
        val slow = AtomicLong(-1)
        q.setSlowListener { ms, _ -> slow.set(ms) }
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        // a renderer / X request thread holding the lock for 1.5 s (the reports' case)
        val holder = Thread { xLock.lock(); try { held.countDown(); release.await(1500, TimeUnit.MILLISECONDS) } finally { xLock.unlock() } }
        holder.start(); held.await()
        var t = System.nanoTime()
        q.post(op("press"))
        var sendNs = System.nanoTime() - t
        Thread.sleep(1_100)                                       // the press now waits on the worker, past SLOW_MS
        t = System.nanoTime()
        for (i in 1..200) { q.moveBy(1, 2); q.moveTo(i, i) }
        q.post(op("release"))
        sendNs += System.nanoTime() - t
        val sendMs = sendNs / 1_000_000.0
        println("XInputQueueTest: the sender spent %.2f ms sending 402 inputs while the X lock was held 1.5 s (before: it waited the lock out)".format(sendMs))
        assertTrue("the sender waited $sendMs ms", sendMs < 100.0)
        assertTrue("the slow op is told once (${slow.get()} ms)", slow.get() >= XInputQueue.SLOW_MS)
        release.countDown(); holder.join()
        assertTrue(q.awaitIdle(5_000))
        // in order; each run of moves merged (an absolute move after a relative one starts a new item, so 200 pairs stay pairs)
        assertEquals("press", log.first()); assertEquals("release", log.last())
        assertEquals(402, log.size)
        assertEquals("by 1,2", log[1]); assertEquals("to 1,1", log[2]); assertEquals("to 200,200", log[log.size - 2])
    }

    @Test fun movesNotStartedYetMergeButKeysAndButtonsNever() {
        val q = XInputQueue("XInputTest2", mover)
        val gate = CountDownLatch(1)
        q.post { gate.await(2, TimeUnit.SECONDS) }                // the worker busy: everything below queues
        Thread.sleep(50)
        for (i in 1..1_000) q.moveBy(1, -1)
        q.post(op("key down")); q.post(op("key up"))
        for (i in 1..1_000) q.moveTo(i, 2 * i)
        q.post(op("button"))
        assertEquals(5, q.pending())                              // 1000 deltas -> 1, 2 keys, 1000 positions -> 1, 1 button
        gate.countDown()
        assertTrue(q.awaitIdle(5_000))
        assertEquals(listOf("by 1000,-1000", "key down", "key up", "to 1000,2000", "button"), log.toList())
        assertTrue(q.stats().contains("merged 1998"))
    }

    @Test fun inputSentFromTheWorkerRunsAtOnceInOrder() {
        val q = XInputQueue("XInputTest3", mover)
        var onWorker = false
        q.post { log += "outer"; q.post(op("inner")); log += "after inner"; onWorker = XInputQueue.onInputThread() }
        q.post(op("next"))
        assertTrue(q.awaitIdle(5_000))
        assertEquals(listOf("outer", "inner", "after inner", "next"), log.toList())
        assertTrue(onWorker)
        assertTrue(!XInputQueue.onInputThread())
    }

    @Test fun aFailingOpDoesNotStopTheQueue() {
        val q = XInputQueue("XInputTest4", mover)
        q.post { throw IllegalStateException("boom") }
        q.post(op("still here"))
        assertTrue(q.awaitIdle(5_000))
        assertEquals(listOf("still here"), log.toList())
    }
}
