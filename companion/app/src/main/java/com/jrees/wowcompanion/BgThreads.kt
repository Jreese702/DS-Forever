package com.jrees.wowcompanion

import java.util.concurrent.ThreadFactory

/**
 * THE SCREEN FIRST (the user, 2026-11-07, after test4: "leave enough memory so that visually the animations on the loading screen are
 * still running smoothly"). The art run, its download and decode pools, the icon packs, the game update and the indoor-map bake all ran
 * at about the UI's own priority, so on the Thor's few big cores they took the frames from the loading screen's animations. Every such
 * thread now runs at Android's BACKGROUND priority (the scheduler's background group): it still uses every idle core, but the main and
 * render threads always go first.
 */
object BgThreads {
    /** lowers the CALLING thread; safe anywhere (a refusal is ignored) */
    fun lower() {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
    }

    /** a pool's factory: daemon threads named [name]-N, each lowered as it starts */
    fun factory(name: String): ThreadFactory {
        val n = java.util.concurrent.atomic.AtomicInteger()
        return ThreadFactory { r -> Thread({ lower(); r.run() }, "$name-${n.incrementAndGet()}").apply { isDaemon = true } }
    }

    /** runs [block] with the calling thread lowered, then puts its priority back (for a coroutine on a shared IO thread) */
    fun <T> lowered(block: () -> T): T {
        val tid = android.os.Process.myTid()
        val was = runCatching { android.os.Process.getThreadPriority(tid) }.getOrNull()
        lower()
        try { return block() } finally {
            if (was != null) runCatching { android.os.Process.setThreadPriority(was) }
        }
    }
}
