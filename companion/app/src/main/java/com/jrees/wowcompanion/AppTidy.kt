package com.jrees.wowcompanion

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * THE APP TIDIES UP AFTER ITSELF (the user's ruling 2026-11-18: 217 launcher setup backups = 611 MB, two 150 MB heap dumps lingering).
 * Once per app start, on a background thread, with the game not running: each [Rule] looks at the direct children of ONE of our own
 * folders, by name, and removes what is past its cap. Never followed: a link (never removed, never walked). Never removed: anything
 * changed in the last [MIN_AGE_MS] (24 h), the newest [Rule.keep] of a rule, anything that does not match its rule's name, anything that
 * is not inside the rule's folder. The game's own files are never a rule's folder. One WCTIDY line says what was freed.
 *
 * The caps ([rules]):
 *  - /sdcard/WowCompanion/setup/launcher-<millis> (LauncherSetup's add-on backup before every update): the newest 5.
 *  - /sdcard/WowCompanion/logs/heap-*.hprof (MemWatch's test-build heap dump): older than 7 days goes.
 *  - Documents/WoW-AddOns-backup/settings-<date> (SvKeep's backup folder at every start): the newest 10.
 *  - Documents/DS Forever/reports (each .zip) (Send report; also the app's own reports folder): the newest 10; a "*.zip.part" left by a
 *    stopped write goes after 24 h.
 *  - the app's stale test-build art folders (files/art-direct*, from the direct-install test builds) when that test is off.
 * Already capped elsewhere (left alone here): the logs' one .1 rotation (BgAppend), mem.log (CappedLog), General.prev / prev2 (LogTail),
 * crash reports (CrashLogs 10, crash_logs), sync-backups (SvBackup 5 per file), the update APK (one), add-on downloads (freed once in).
 * Never touched: Documents/WoW-AddOns-backup/shadow-* (the player's own game files moved out of the shadow) and the game folder's
 * AddOns-backup-dsforever (the "never deleted" add-on backups).
 * Pure ([run] on java.io): dsforever's AppTidyTest runs it in temp folders.
 */
object AppTidy {
    private const val TAG = "WCTIDY"
    const val MIN_AGE_MS = 24L * 3600_000
    const val HPROF_MAX_AGE_MS = 7L * 24 * 3600_000
    const val LAUNCHER_KEEP = 5
    const val SETTINGS_KEEP = 10
    const val REPORTS_KEEP = 10
    private const val START_DELAY_MS = 20_000L
    private const val RETRY_MS = 60_000L

    /**
     * [dir]'s direct children whose name matches [name]: the newest [keep] stay (newest by [time]); of the rest, those older than
     * [maxAgeMs] (null = any age) go. [MIN_AGE_MS] holds for every rule.
     */
    data class Rule(val label: String, val dir: File, val name: Regex, val keep: Int, val maxAgeMs: Long? = null,
                    val time: (File) -> Long = { it.lastModified() })

    data class Result(val files: Int, val bytes: Long, val per: Map<String, Int>, val stopped: Boolean = false) {
        val line: String get() = when {
            files == 0 -> "tidy: nothing to free" + if (stopped) " (stopped: the game started)" else ""
            else -> "tidy: freed ${mb(bytes)} MB (" + per.filterValues { it > 0 }.entries.joinToString(", ") { "${it.key} ${it.value}" } + ")" +
                if (stopped) " (stopped: the game started)" else ""
        }
        private fun mb(b: Long) = if (b < 10L * 1024 * 1024) "%.1f".format(Locale.US, b / 1048576.0) else (b / 1048576).toString()
    }

    /** the millis in a "launcher-<millis>" name, else the file's own time */
    fun launcherTime(f: File): Long = f.name.removePrefix("launcher-").toLongOrNull() ?: f.lastModified()

    /** the time in a "settings-yyyy-MM-dd_HHmmss" name, else the file's own time */
    fun settingsTime(f: File): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).apply { isLenient = false }.parse(f.name.removePrefix("settings-"))!!.time
    }.getOrDefault(f.lastModified())

    /** the rules on the device ([docs] Documents, [sdcard] the shared storage root, [files] / [external] the app's own folders) */
    fun rules(docs: File, sdcard: File, files: File, external: File?, artTestOn: Boolean): List<Rule> = buildList {
        add(Rule("launcher backups", File(sdcard, "WowCompanion/setup"), Regex("""launcher-\d+"""), LAUNCHER_KEEP, time = ::launcherTime))
        add(Rule("heap dumps", File(sdcard, "WowCompanion/logs"), Regex("""heap-.*\.hprof"""), 0, HPROF_MAX_AGE_MS))
        add(Rule("settings backups", File(docs, "WoW-AddOns-backup"), Regex("""settings-\d{4}-\d\d-\d\d_\d{6}"""), SETTINGS_KEEP, time = ::settingsTime))
        for (d in listOfNotNull(File(docs, "DS Forever/reports"), external?.let { File(it, "reports") })) {
            add(Rule("reports", d, Regex(""".+\.zip"""), REPORTS_KEEP))
            add(Rule("report parts", d, Regex(""".+\.zip\.part"""), 0))
        }
        if (!artTestOn) add(Rule("old test art", files, Regex("""art-direct\d*"""), 0))
    }

    private fun isLink(f: File) = runCatching { Files.isSymbolicLink(f.toPath()) }.getOrDefault(true)

    /** [f] and everything under it (a link inside is removed as itself, never walked); the bytes freed, or -1 if anything stayed */
    private fun remove(f: File): Long {
        var freed = 0L
        var ok = true
        if (!isLink(f) && f.isDirectory) {
            for (c in f.listFiles().orEmpty()) { val n = remove(c); if (n < 0) ok = false else freed += n }
        }
        val size = if (!isLink(f) && f.isFile) f.length() else 0L
        if (f.delete()) freed += size else ok = false
        return if (ok) freed else -1L - freed
    }

    /** the newest change of [f] and anything under it (links not walked): a backup still being written counts as new */
    private fun newest(f: File, depth: Int = 0): Long {
        var t = f.lastModified()
        if (depth < 8 && !isLink(f) && f.isDirectory) for (c in f.listFiles().orEmpty()) t = maxOf(t, newest(c, depth + 1))
        return t
    }

    /**
     * Every rule once, at [now]. [stop] asked between removals (the game came up): the rest waits for the next start.
     */
    fun run(rules: List<Rule>, now: Long, stop: () -> Boolean = { false }): Result {
        var files = 0
        var bytes = 0L
        val per = LinkedHashMap<String, Int>()
        for (r in rules) {
            per.putIfAbsent(r.label, 0)
            if (isLink(r.dir) || !r.dir.isDirectory) continue
            val root = runCatching { r.dir.canonicalFile }.getOrNull() ?: continue
            val all = r.dir.listFiles()?.filter { it.name.matches(r.name) && !isLink(it) }.orEmpty()
            val gone = all.sortedByDescending { r.time(it) }.drop(r.keep).filter { f ->
                val inside = runCatching { f.canonicalFile.parentFile == root }.getOrDefault(false)
                val t = maxOf(r.time(f), newest(f))
                inside && now - t >= MIN_AGE_MS && (r.maxAgeMs == null || now - t >= r.maxAgeMs)
            }
            for (f in gone) {
                if (stop()) return Result(files, bytes, per, stopped = true)
                val n = remove(f)
                if (n >= 0) { files++; bytes += n; per[r.label] = (per[r.label] ?: 0) + 1 } else bytes += -1L - n
            }
        }
        return Result(files, bytes, per)
    }

    @Volatile private var started = false

    /** at app start: once, ~20 s in, on its own low-priority thread; while the game runs it waits (never during its writes) */
    fun startInBackground(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        Thread({
            runCatching {
                Thread.sleep(START_DELAY_MS)
                while (DependencyAddons.gameRunning()) Thread.sleep(RETRY_MS)
                @Suppress("DEPRECATION")
                val sdcard = Environment.getExternalStorageDirectory()
                val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val r = run(rules(docs, sdcard, app.filesDir, app.getExternalFilesDir(null), DirectTest.ON), System.currentTimeMillis()) {
                    DependencyAddons.gameRunning()
                }
                Log.i(TAG, r.line)
                runCatching { DiagLog.init(app); DiagLog.event("tidy", r.line) }
            }.onFailure { Log.w(TAG, "tidy: not run (${it.javaClass.simpleName}: ${it.message})") }
        }, "app-tidy").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }
}
