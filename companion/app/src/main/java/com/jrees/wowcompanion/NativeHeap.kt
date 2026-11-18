package com.jrees.wowcompanion

import java.io.File

/**
 * DS FOREVER'S NATIVE HEAP HANDED BACK TO ANDROID (test build 1489, the user's pick 2026-11-18, #56): the allocator keeps pages that
 * were freed (CascLib's ~450 MB of index tables after an art run, a decoded picture's buffers) and Android counts them as ours. At each
 * memory relief (MemWatch.relieve: the game's start, its world entry, each loading screen, the art's let-go) [purgeLine] asks bionic to
 * return them (libdsf-heap.so, dsforever's ndkBuild: mallopt M_PURGE_ALL / M_PURGE) and says what the process's resident size did.
 * The 1.0.33 / 1.0.34 reports, game up: native 147 MB resident with 83 MB in use (median), the heap grown to 263 MB (p90 368).
 * Without the library (a build with no ndkBuild, the off-device tests) nothing happens and the line is empty.
 */
object NativeHeap {
    private val loaded: Boolean by lazy { runCatching { System.loadLibrary("dsf-heap"); true }.getOrDefault(false) }

    @JvmStatic private external fun purge(): Int

    /** the library is in the APK */
    val available: Boolean get() = loaded

    /** this process's resident size (/proc/self/statm, pages), bytes; -1 = not readable */
    internal fun rssBytes(statm: String?): Long =
        statm?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.let { it * 4096L } ?: -1L

    private fun rssNow(): Long = rssBytes(runCatching { File("/proc/self/statm").readText() }.getOrNull())

    /** "native purge: rss 812 -> 761 MB" (pure: the tests); [how] = the purge's answer (0 = not supported) */
    internal fun line(how: Int, before: Long, after: Long): String = when {
        how <= 0 -> "native purge: not supported"
        before < 0 || after < 0 -> "native purge: done"
        else -> "native purge: rss ${MemWatch.mbB(before)} -> ${MemWatch.mbB(after)} MB"
    }

    /** the purge with its line for the relief's mem.log line, "" without the library; any thread (MemWatch's own) */
    fun purgeLine(): String {
        if (!loaded) return ""
        val before = rssNow()
        val how = runCatching { purge() }.getOrDefault(0)
        return line(how, before, rssNow())
    }
}
