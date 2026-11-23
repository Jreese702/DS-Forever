package com.winlator.xserver;

import java.util.ArrayDeque;

/**
 * ONE QUEUE FOR EVERY INPUT SENT TO THE X SERVER (#153, 1.0.37's "DS Forever stopped responding" reports: 3 of 6 had the main thread
 * parked in XServer.lock for 5 s on a MotionEvent - a touch or a pad press calling XServer.inject*, which takes WINDOW_MANAGER +
 * INPUT_DEVICE while an X request thread or the renderer held WINDOW_MANAGER). Every key, pointer button and pointer move now goes
 * through this FIFO and runs on its own thread ("XInput"), so the thread that sends it - the UI thread above all - never waits on an X
 * lock. Order is kept: one worker, first in first out, and a call made ON the worker (an input op that sends another) runs at once.
 *
 * Pointer moves that have not started yet are merged into the newest one of the same kind (absolute: the last position wins; relative:
 * the deltas add up), so a stalled X lock never builds a backlog of thousands of moves; keys and buttons are never merged or dropped.
 * A relative sum is kept for [STALE_MOVE_MS] only: past that, the deltas added so far are dropped and the sum starts again from the
 * newest one, so a stick held through a 5 s stall does not land as one 3000 px jump when the lock frees (the cursor pauses instead).
 * The worker leaves after [IDLE_EXIT_MS] with nothing to do and is started again by the next input (no thread left behind by an
 * X server that is gone). An op that has run for [SLOW_MS] is told to the [SlowListener] once (XServer logs who holds the X locks).
 * Pure Java (the tests drive it with plain locks).
 */
public final class XInputQueue {
    /** an op has been running this long: told once to the listener */
    public static final long SLOW_MS = 1_000L;
    /** the worker leaves after this long with nothing queued */
    public static final long IDLE_EXIT_MS = 30_000L;
    /** past this many queued items a new pointer move is dropped (keys and buttons never are) */
    public static final int MAX_QUEUED = 4_096;
    /** a queued relative move older than this drops its sum and starts again from the newest delta (a stall is not replayed) */
    public static final long STALE_MOVE_MS = 250L;

    /** where the merged pointer moves land (XServer's own, run on the worker) */
    public interface Mover {
        void moveTo(int x, int y);
        void moveBy(int dx, int dy);
    }

    /** an op has run [waitedMs] on [worker] (called on the thread that queued the next input: keep it short, hand the work on) */
    public interface SlowListener {
        void onSlow(long waitedMs, Thread worker);
    }

    private static final int OP = 0, MOVE_TO = 1, MOVE_BY = 2;

    private static final class Item {
        final int kind;
        int a, b;
        final Runnable op;
        final long queuedNs;
        long sumFromNs;                          // MOVE_BY: when the deltas in [a], [b] started adding up
        Item(int kind, int a, int b, Runnable op, long queuedNs) { this.kind = kind; this.a = a; this.b = b; this.op = op; this.queuedNs = queuedNs; this.sumFromNs = queuedNs; }
    }

    private final String name;
    private final Mover mover;
    private final ArrayDeque<Item> queue = new ArrayDeque<>();
    private volatile Thread worker;
    private volatile long busySinceNs;           // 0 = idle
    private volatile boolean slowSaid;
    private volatile SlowListener slowListener;
    // the counts (stats(): a report line; the tests)
    private long ran, merged, dropped, stale, maxWaitNs, maxRunNs;

    public XInputQueue(String name, Mover mover) {
        this.name = name;
        this.mover = mover;
    }

    public void setSlowListener(SlowListener l) { slowListener = l; }

    /** a queue's worker (OutputQueue: a write from it never blocks on a client that does not read) */
    static final class InputThread extends Thread {
        InputThread(Runnable r, String name) { super(r, name); }
    }

    /** the caller is any input queue's worker */
    public static boolean onInputThread() { return Thread.currentThread() instanceof InputThread; }

    /** the caller is this queue's worker: an input op sending more input runs it at once (the order is the worker's own) */
    public boolean isInputThread() { return Thread.currentThread() == worker; }

    /** [op] on the worker, after everything queued before it (at once when already on the worker) */
    public void post(Runnable op) {
        if (isInputThread()) { op.run(); return; }
        enqueue(OP, 0, 0, op);
    }

    /** the pointer to [x], [y] (merged with a queued absolute move not started yet) */
    public void moveTo(int x, int y) {
        if (isInputThread()) { mover.moveTo(x, y); return; }
        enqueue(MOVE_TO, x, y, null);
    }

    /** the pointer by [dx], [dy] (added to a queued relative move not started yet) */
    public void moveBy(int dx, int dy) {
        if (isInputThread()) { mover.moveBy(dx, dy); return; }
        enqueue(MOVE_BY, dx, dy, null);
    }

    private void enqueue(int kind, int a, int b, Runnable op) {
        long now = System.nanoTime();
        synchronized (this) {
            Item tail = queue.peekLast();
            if (kind != OP && tail != null && tail.kind == kind) {
                if (kind == MOVE_TO) {
                    tail.a = a; tail.b = b;
                } else if (now - tail.sumFromNs > STALE_MOVE_MS * 1_000_000L) {
                    tail.a = a; tail.b = b; tail.sumFromNs = now;    // the stalled stretch's deltas are dropped, not replayed
                    stale++;
                } else {
                    tail.a += a; tail.b += b;
                }
                merged++;
            } else if (kind != OP && queue.size() >= MAX_QUEUED) {
                dropped++;
            } else {
                queue.addLast(new Item(kind, a, b, op, now));
            }
            if (worker == null) {
                Thread t = new InputThread(this::loop, name);
                t.setDaemon(true);
                worker = t;
                t.start();
            }
            notifyAll();
        }
        checkSlow(now);
    }

    private void checkSlow(long now) {
        long since = busySinceNs;
        if (since == 0L || slowSaid || now - since < SLOW_MS * 1_000_000L) return;
        slowSaid = true;
        SlowListener l = slowListener;
        Thread w = worker;
        if (l != null && w != null) {
            try { l.onSlow((now - since) / 1_000_000L, w); } catch (Throwable ignored) {}
        }
    }

    private void loop() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY); } catch (Throwable ignored) {}
        while (true) {
            Item it;
            synchronized (this) {
                long idleFrom = System.nanoTime();
                while (queue.isEmpty()) {
                    notifyAll();                                  // awaitIdle
                    long left = IDLE_EXIT_MS - (System.nanoTime() - idleFrom) / 1_000_000L;
                    if (left <= 0L) { worker = null; return; }
                    try { wait(left); } catch (InterruptedException e) { worker = null; return; }
                }
                it = queue.pollFirst();
                long start = System.nanoTime();
                maxWaitNs = Math.max(maxWaitNs, start - it.queuedNs);
                slowSaid = false;
                busySinceNs = start;
            }
            try {
                switch (it.kind) {
                    case MOVE_TO: mover.moveTo(it.a, it.b); break;
                    case MOVE_BY: if (it.a != 0 || it.b != 0) mover.moveBy(it.a, it.b); break;
                    default: it.op.run();
                }
            } catch (Throwable t) {
                try { android.util.Log.w("WCXIN", "an input op failed: " + t); } catch (Throwable ignored) {}
            } finally {
                long end = System.nanoTime();
                synchronized (this) {
                    maxRunNs = Math.max(maxRunNs, end - busySinceNs);
                    busySinceNs = 0L;
                    ran++;
                }
            }
        }
    }

    /** the queue is empty and nothing runs, within [timeoutMs] (the tests; never on the UI thread) */
    public boolean awaitIdle(long timeoutMs) throws InterruptedException {
        long end = System.nanoTime() + timeoutMs * 1_000_000L;
        synchronized (this) {
            while (!queue.isEmpty() || busySinceNs != 0L) {
                long left = (end - System.nanoTime()) / 1_000_000L;
                if (left <= 0L) return false;
                wait(Math.min(left, 20L));
            }
            return true;
        }
    }

    /** how many items wait now */
    public synchronized int pending() { return queue.size(); }

    /** "input ran 120, merged 40, dropped 0, stale 0, wait max 3 ms, run max 1 ms" since the last call (the counts start again) */
    public synchronized String stats() {
        String s = "input ran " + ran + ", merged " + merged + ", dropped " + dropped + ", stale " + stale + ", wait max " +
            maxWaitNs / 1_000_000L + " ms, run max " + maxRunNs / 1_000_000L + " ms";
        ran = 0; merged = 0; dropped = 0; stale = 0; maxWaitNs = 0; maxRunNs = 0;
        return s;
    }
}
