package com.winlator.xconnector;

import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * DS FOREVER #481 (1.0.33 "stopped responding", Input dispatching timed out): a touch on the game screen moved the X pointer ON THE
 * MAIN THREAD (TouchpadView -> XServer.injectPointerMoveDelta -> InputDeviceEvent.send -> XOutputStream.flush), and the socket write
 * blocked for 2.5 .. 11.5 s because the game was not reading its X connection. A write from a thread that must not block (the main
 * thread) is now COPIED into this queue and written by a background drain; every other thread writes directly as before while the
 * queue is idle, and queues behind it while it is not, so the bytes still reach the client in the order they were flushed. A flush is
 * whole messages (one XStreamLock), so a main-thread chunk dropped past [MAX_QUEUED_BYTES] (a client that has not read for minutes)
 * never splits a message. Ancillary-fd messages (ALSA, SysV shm; never the main thread) are never deferred: their fd may be closed
 * after the flush returns, so they wait for the queue to drain and go directly.
 * Not covered (unchanged): a server thread's own direct write that blocks still holds the stream lock (and any XServer lock its
 * request took), so a main-thread flush to that same client waits for it. A reply only follows that client's request, so the client
 * is reading; the report's case was an event to a client that was not.
 */
final class OutputQueue {
    interface Sink {
        void write(ByteBuffer data) throws IOException;
        void sendAncillaryMsg(ByteBuffer data, int ancillaryFd) throws IOException;
    }

    static final int MAX_QUEUED_BYTES = 2 * 1024 * 1024;
    private static final ExecutorService DRAINS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "x-out-drain");
        t.setDaemon(true);
        return t;
    });

    private static final class Chunk {
        final ByteBuffer data;
        Chunk(ByteBuffer data) { this.data = data; }
    }

    private final Sink sink;
    private final BooleanSupplier mustNotBlock;
    private final Executor executor;
    private final int maxQueuedBytes;
    private final ArrayDeque<Chunk> queue = new ArrayDeque<>();
    private int queuedBytes;
    private boolean busy;        // a write is under way (a direct one, or the drain)
    private boolean broken;      // the drain's write failed: the client is gone
    private long dropped;

    OutputQueue(Sink sink) {
        this(sink, OutputQueue::onMainThread, DRAINS, MAX_QUEUED_BYTES);
    }

    OutputQueue(Sink sink, BooleanSupplier mustNotBlock, Executor executor, int maxQueuedBytes) {
        this.sink = sink;
        this.mustNotBlock = mustNotBlock;
        this.executor = executor;
        this.maxQueuedBytes = maxQueuedBytes;
    }

    static boolean onMainThread() {
        // #153: the X input queue's thread neither (every touch, pad button and key is sent from it now, holding the X locks)
        if (com.winlator.xserver.XInputQueue.onInputThread()) return true;
        Looper main = Looper.getMainLooper();
        return main != null && main.isCurrentThread();
    }

    /** sends [data] (position 0 .. limit, as ClientSocket reads it); [ancillaryFd] -1 = none */
    void send(ByteBuffer data, int ancillaryFd) throws IOException {
        boolean noBlock = ancillaryFd == -1 && mustNotBlock.getAsBoolean();
        synchronized (this) {
            if (ancillaryFd != -1) {
                while (busy) {
                    try { wait(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
                }
            }
            if (noBlock || busy) {
                int n = data.remaining();
                if (broken) return;
                if (noBlock && queuedBytes + n > maxQueuedBytes) {
                    if (dropped++ % 1000 == 0) note("x out: the client is not reading; " + dropped + " main-thread chunk(s) dropped");
                    return;
                }
                ByteBuffer copy = ByteBuffer.allocateDirect(n).order(data.order());
                copy.put(data).flip();
                queue.add(new Chunk(copy));
                queuedBytes += n;
                if (!busy) {
                    busy = true;
                    executor.execute(this::drain);
                }
                return;
            }
            busy = true;
        }
        try {
            if (ancillaryFd != -1) sink.sendAncillaryMsg(data, ancillaryFd);
            else sink.write(data);
        } finally {
            synchronized (this) {
                if (queue.isEmpty()) { busy = false; notifyAll(); }
                else executor.execute(this::drain);     // queued behind this write: the drain keeps busy
            }
        }
    }

    private void drain() {
        for (;;) {
            Chunk c;
            synchronized (this) {
                c = queue.poll();
                if (c == null) { busy = false; notifyAll(); return; }
                queuedBytes -= c.data.remaining();
            }
            try {
                sink.write(c.data);
            } catch (IOException | RuntimeException e) {
                synchronized (this) {
                    broken = true;
                    queue.clear();
                    queuedBytes = 0;
                    busy = false;
                    notifyAll();
                }
                note("x out: queued write failed: " + e);
                return;
            }
        }
    }

    synchronized int queuedBytes() { return queuedBytes; }
    synchronized long dropped() { return dropped; }

    private static void note(String s) {
        try { Log.w("XOutputQueue", s); } catch (Throwable ignored) { }   // JVM tests: android.util.Log is a stub
    }
}
