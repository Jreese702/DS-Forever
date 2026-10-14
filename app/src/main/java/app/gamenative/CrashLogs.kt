package app.gamenative

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

/**
 * CRASH LOGS A PLAYER CAN SHARE (p-casclib-1000, a player's report 2026-10-14: "the bottom screen crashed" and we had no trace).
 * - [save]: a copy of every report in Documents/DS Forever/logs (with the all-files access the game folder already needs; else the
 *   app's own external folder, Android/data/<package>/files/logs), the newest [KEEP] kept.
 * - [recordLastExit]: at the next start, why the last run ended when it was not a normal close (Android 11+'s exit records): a native
 *   crash with its tombstone trace, an ANR with its traces, a low-memory kill, a Java crash. CrashHandler catches only the last kind.
 */
object CrashLogs {
    const val KEEP = 10
    private const val PREFS = "crash_logs"
    private const val KEY_LAST_EXIT = "lastExit"
    private const val MAX_TRACE_BYTES = 1L shl 20

    /** where a player finds them: Documents/DS Forever/logs, or the app's own folder without all-files access */
    fun dir(ctx: Context): File {
        val shared = runCatching {
            if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "DS Forever/logs")
            else null
        }.getOrNull()
        return shared ?: File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "logs")
    }

    /** [text] as [name] in [dir], written whole (temp + rename), the oldest beyond [KEEP] removed; the file, or null */
    fun save(ctx: Context, name: String, text: String): File? = runCatching { write(dir(ctx), name, text) }
        .onFailure { Timber.w(it, "crash log not saved") }.getOrNull()

    fun write(dir: File, name: String, text: String, keep: Int = KEEP): File {
        dir.mkdirs()
        val f = File(dir, name)
        val tmp = File(dir, "$name.part")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        prune(dir, keep)
        return f
    }

    /** the newest [keep] reports stay (by name: they start with their time) */
    fun prune(dir: File, keep: Int = KEEP) {
        val reports = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.sortedByDescending { it.name } ?: return
        reports.drop(keep).forEach { it.delete() }
    }

    fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(ms))

    /** the exit reasons worth a report: crashes (Java and native), ANRs, low-memory kills */
    fun worthSaving(reason: Int): Boolean = reason == ApplicationExitInfo.REASON_CRASH ||
        reason == ApplicationExitInfo.REASON_CRASH_NATIVE || reason == ApplicationExitInfo.REASON_ANR ||
        reason == ApplicationExitInfo.REASON_LOW_MEMORY

    fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native-crash"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
        else -> "exit-$reason"
    }

    /** the last runs' abnormal ends not reported yet (Android 11+); call off the main thread at start */
    fun recordLastExit(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val last = prefs.getLong(KEY_LAST_EXIT, 0L)
            val exits = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5).filter { it.timestamp > last }
            for (e in exits.sortedBy { it.timestamp }) {
                if (!worthSaving(e.reason)) continue
                val text = buildString {
                    appendLine("DS Forever: how the last run ended")
                    appendLine("When: ${stamp(e.timestamp)}")
                    appendLine("Reason: ${reasonName(e.reason)} (${e.reason}), status ${e.status}")
                    appendLine("Description: ${e.description}")
                    appendLine("Process: ${e.processName} (pid ${e.pid}), importance ${e.importance}")
                    appendLine("Memory: pss ${e.pss} KB, rss ${e.rss} KB")
                    appendLine("App: ${BuildConfig.VERSION_NAME}; device ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
                    // the tombstone (native crash) or the ANR traces, when Android kept them
                    runCatching { e.traceInputStream }.getOrNull()?.use { s ->
                        appendLine(); appendLine("---------- Trace ----------")
                        append(String(s.readNBytesCompat(MAX_TRACE_BYTES), Charsets.UTF_8))
                    }
                }
                save(ctx, "${stamp(e.timestamp)}_${reasonName(e.reason)}.txt", text)
                    ?.let { Timber.i("last run ended (${reasonName(e.reason)}): saved to $it") }
            }
            exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong(KEY_LAST_EXIT, it).apply() }
        }.onFailure { Timber.w(it, "exit records") }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        var left = max
        while (left > 0) { val n = read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; out.write(buf, 0, n); left -= n }
        return out.toByteArray()
    }
}
