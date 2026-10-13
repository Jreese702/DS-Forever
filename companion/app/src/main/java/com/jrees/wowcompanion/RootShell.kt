package com.jrees.wowcompanion

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * ONE LONG-LIVED ROOT SHELL for the app's frequent short commands (the user, 2026-10-06: "The Superuser pop ups are obnoxious";
 * measured: a NEW `su` per tap, per key and per check, one root request about every 12 s, each one a Magisk toast). Every batch runs on
 * the same `su --mount-master` process, one batch at a time on its own thread (so the commands of one game action never interleave
 * with another's), and ends with a unique marker line carrying the batch's exit code. A dead or stuck shell is killed and the next
 * batch opens a new one: Magisk is asked again only then.
 *
 * Callers never block the main thread: [run] answers on this shell's thread through [done].
 */
object RootShell {
    private const val TAG = "RootShell"
    private val thread = HandlerThread("rootshell").apply { start() }
    private val handler = Handler(thread.looper)
    private var proc: Process? = null
    private var out: OutputStream? = null
    private var reader: BufferedReader? = null
    private var seq = 0
    /** batches that found no shell (refused, or the open failed): for [HomeRoot]'s word */
    @Volatile var lastOpenFailed = false; private set

    /** a shell is open and its process alive: root is ours now (HomeRoot asks this before a su of its own) */
    fun alive(): Boolean = proc?.let { p -> try { p.exitValue(); false } catch (_: IllegalThreadStateException) { true } } ?: false

    /** the answer of one batch: its exit code (-1 = no shell, or it died / timed out) and its output lines */
    data class Result(val exit: Int, val lines: List<String>)

    /** runs [script] (one or more lines) as root; [done] gets the result on the shell's thread */
    fun run(script: String, timeoutMs: Long = 10_000L, done: (Result) -> Unit = {}) {
        handler.post { done(runNow(script, timeoutMs)) }
    }

    /** the same, BLOCKING: only for callers already on a worker thread of their own */
    fun runBlocking(script: String, timeoutMs: Long = 10_000L): Result {
        if (Thread.currentThread() === thread) return runNow(script, timeoutMs)
        val lock = Object()
        var res: Result? = null
        handler.post { val r = runNow(script, timeoutMs); synchronized(lock) { res = r; lock.notifyAll() } }
        synchronized(lock) { while (res == null) lock.wait(timeoutMs + 2_000L).also { if (res == null) return Result(-1, emptyList()) } }
        return res!!
    }

    private fun runNow(script: String, timeoutMs: Long): Result {
        // inside the launcher (the private fork, phase 3b): taps and keys go to its X server, never to a shell
        if (InputSink.sink != null) return InputSink.play(script)
        val o = out ?: open() ?: return Result(-1, emptyList())
        val r = reader ?: return Result(-1, emptyList())
        val marker = "__WCEND_${++seq}_"
        // the watchdog: a batch past its time kills the shell (readLine then ends); the next batch opens a new one
        val p = proc
        val kill = Runnable { Log.w(TAG, "a batch ran past ${timeoutMs} ms: the shell is closed"); try { p?.destroy() } catch (_: Throwable) {} }
        watchdog.postDelayed(kill, timeoutMs)
        try {
            o.write("$script\necho \"$marker$?\"\n".toByteArray()); o.flush()
            val lines = ArrayList<String>()
            while (true) {
                val line = r.readLine() ?: run { close(); return Result(-1, lines) }
                if (line.startsWith(marker)) return Result(line.removePrefix(marker).trim().toIntOrNull() ?: -1, lines)
                lines += line
            }
        } catch (t: Throwable) {
            close(); return Result(-1, emptyList())
        } finally {
            watchdog.removeCallbacks(kill)
        }
    }

    // the watchdog runs on its own thread: the shell's thread is the one blocked in readLine
    private val watchdog = Handler(HandlerThread("rootshell-dog").apply { start() }.looper)

    private fun open(): OutputStream? = if (!RootPolicy.allowed) null else try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "--mount-master"))
        proc = p
        reader = BufferedReader(InputStreamReader(p.inputStream))
        p.outputStream.also { out = it; it.write("exec 2>/dev/null\n".toByteArray()); it.flush() }.also { lastOpenFailed = false }
    } catch (t: Throwable) {
        lastOpenFailed = true; close(); null
    }

    private fun close() {
        try { out?.write("exit\n".toByteArray()); out?.flush() } catch (_: Throwable) {}
        try { proc?.destroy() } catch (_: Throwable) {}
        proc = null; out = null; reader = null
    }

    /** the app goes for good: the shell closes */
    fun shutdown() { handler.post { close() } }
}
