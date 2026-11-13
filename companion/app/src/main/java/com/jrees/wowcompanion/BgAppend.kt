package com.jrees.wowcompanion

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A LOG FILE WRITTEN OFF THE CALLER'S THREAD (the user, 1.0.29 test build: "insane input lag on my face buttons"). Every pad KEY press
 * reached the game only after LatLog had done mkdirs + length + append on /sdcard through FUSE, on the main thread, under a lock the
 * InputSink worker shared. Now a caller only puts its line in a lock-free queue ([add]: no I/O, no lock, no wait); ONE background thread
 * ([EXEC], shared by every such file) appends what came in every [flushMs] in one buffered write, and rotates there. A full queue
 * ([cap] lines) drops the line and counts it ([dropped]); the next write says how many went.
 */
class BgAppend(
    private val file: () -> File,
    private val rotateAt: Long = 1_000_000L,
    private val cap: Int = 4096,
    private val flushMs: Long = 500L,
    /** the write itself (tests: a slow one); called on [EXEC] only */
    private val write: (File, List<String>, Long) -> Unit = ::appendLines,
) {
    private val queue = ConcurrentLinkedQueue<String>()
    private val size = AtomicInteger(0)
    private val scheduled = AtomicBoolean(false)
    private val droppedNow = AtomicLong(0)
    /** lines dropped because the queue was full (the run's total) */
    val dropped = AtomicLong(0)

    /** queue [line] (no newline needed); never touches the file, never blocks */
    fun add(line: String) {
        if (size.incrementAndGet() > cap) { size.decrementAndGet(); dropped.incrementAndGet(); droppedNow.incrementAndGet() }
        else queue.offer(line)
        if (scheduled.compareAndSet(false, true)) {
            try { EXEC.schedule({ drain() }, flushMs, TimeUnit.MILLISECONDS) } catch (_: Throwable) { scheduled.set(false) }
        }
    }

    /** lines waiting (tests) */
    fun pending(): Int = size.get()

    /** tests: write what is queued now, on the writer thread, and wait for it */
    fun flushNow() { EXEC.submit { drain() }.get(10, TimeUnit.SECONDS) }

    private fun drain() {
        scheduled.set(false)                                      // a line added from now on schedules the next batch
        val batch = ArrayList<String>()
        while (true) { val l = queue.poll() ?: break; size.decrementAndGet(); batch.add(l) }
        val lost = droppedNow.getAndSet(0)
        if (lost > 0) batch.add("(${lost} lines dropped: the log's queue was full)")
        if (batch.isEmpty()) return
        runCatching { write(file(), batch, rotateAt) }
    }

    companion object {
        /** the one writer thread of every [BgAppend] (and other small background log work) */
        val EXEC: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, WRITER).apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
        /** the writer thread's name (DiagLog writes at once when it is already on it) */
        const val WRITER = "WCLOGW"

        /** append [lines] in one buffered write; past [rotateAt] bytes the file goes to <name>.1 first */
        fun appendLines(f: File, lines: List<String>, rotateAt: Long) {
            f.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
            if (rotateAt > 0 && f.length() > rotateAt) f.renameTo(File(f.path + ".1"))
            java.io.FileOutputStream(f, true).bufferedWriter().use { w -> for (l in lines) { w.write(l); w.write("\n") } }
        }
    }
}
