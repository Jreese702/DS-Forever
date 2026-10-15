package com.jrees.wowcompanion

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "SEND A PROBLEM REPORT" (board f-problem-report; the user's picks). The player says what happened; DS Forever bundles the last
 * [WINDOW_MS] of its own diagnostic log ([DiagLog]) and its own logcat lines ([ourTag]), the newest crash reports, the versions and the
 * device, ALL through [Redact]. It is shown before anything is saved; it is saved only where the player chooses (Documents/DS Forever/
 * reports) and a GitHub issue is only opened in the browser, pre-filled, for them to attach the file. DS Forever uploads nothing.
 */
object ProblemReport {
    const val WINDOW_MS = 15 * 60_000L
    const val REPO = "Jreese702/DS-Forever"
    private const val MAX_DESCRIPTION_IN_URL = 1500
    private const val MAX_CRASHES = 3

    data class Facts(val app: String, val addons: String, val game: String, val device: String, val settings: List<String>)

    /** the report's text parts: (file name in the zip, content), every one redacted */
    data class Report(val name: String, val files: List<Pair<String, String>>) {
        val preview: String get() = files.joinToString("\n\n") { (n, t) -> "===== $n =====\n$t" }
    }

    /** our own logcat tags only (the add-on bridge, the art, the indoor maps, the sign-in steps, the launcher's own); never chat */
    fun ourTag(tag: String): Boolean {
        val t = tag.trim()
        if (Regex("(?i)chat|whisper|social|friend").containsMatchIn(t)) return false
        return t.startsWith("WC") || t.startsWith("DSF") || t in setOf("DSFORK", "FrameGen", "LsfgVkManager", "WoWForeverScreen",
            "CrashLogs", "WowClientDownloader", "AppUpdater", "DSForever")
    }

    /** a `logcat -v threadtime` line's tag ("10-14 12:00:00.000  123  456 I WCHARVEST: text"), or null */
    fun tagOf(line: String): String? =
        Regex("""^\S+\s+\S+\s+\d+\s+\d+\s+[VDIWEF]\s+([^:]+?)\s*:""").find(line)?.groupValues?.get(1)

    /** the report from its parts: pure (the tests build one from strings) */
    fun build(now: Long, description: String, facts: Facts, diag: List<String>, logcat: List<String>,
              crashes: List<Pair<String, String>>, recorded: Boolean, names: Collection<String>): Report {
        val pattern = Redact.namePattern(names)                       // compiled once for the whole report
        val r = { s: String -> Redact.text(s, pattern) }
        val head = buildString {
            appendLine("DS Forever problem report, ${stamp(now)}")
            appendLine()
            appendLine("What happened (the player's words):")
            appendLine(r(description.trim()).ifEmpty { "(not filled in)" })
            appendLine()
            appendLine("DS Forever: ${facts.app}")
            appendLine("Add-ons: ${facts.addons}")
            appendLine("Game: ${r(facts.game)}")
            appendLine("Device: ${facts.device}")
            appendLine("Recorded in detail: ${if (recorded) "yes (Record 5 minutes)" else "no"}")
            if (facts.settings.isNotEmpty()) { appendLine(); appendLine("Settings and state:"); facts.settings.forEach { appendLine("  ${r(it)}") } }
            appendLine()
            appendLine("Everything here went through DS Forever's privacy pass: logins, BattleTags, character, realm, guild and party names,")
            appendLine("chat text and IP addresses are replaced by <email>, <battletag>, <name>, <text>, <ip>.")
        }
        val logs = (diag.map { "diag  $it" } + logcat.filter { l -> tagOf(l)?.let(::ourTag) == true }.map { "log   $it" })
            .joinToString("\n") { r(it) }
        val files = ArrayList<Pair<String, String>>()
        files += "report.txt" to head
        files += "logs.txt" to logs.ifEmpty { "(no log lines in the last ${WINDOW_MS / 60_000} minutes)" }
        for ((n, t) in crashes.take(MAX_CRASHES)) files += "crashes/$n" to r(t)
        return Report("ds-forever-report-${stamp(now)}", files)
    }

    /** the browser's pre-filled issue: the title, the version and device, the player's words (redacted, shortened), the file to attach */
    fun issueUrl(report: Report, description: String, facts: Facts, savedAs: String?, names: Collection<String>): String {
        val words = Redact.text(description.trim(), names).let { if (it.length > MAX_DESCRIPTION_IN_URL) it.take(MAX_DESCRIPTION_IN_URL) + "…" else it }
        val title = "Problem report: " + (words.lineSequence().firstOrNull()?.take(70)?.ifEmpty { null } ?: "DS Forever ${facts.app}")
        val body = buildString {
            appendLine("**What happened**"); appendLine(words.ifEmpty { "(please describe it)" }); appendLine()
            appendLine("**DS Forever** ${facts.app} · add-ons ${facts.addons} · ${facts.device}"); appendLine()
            appendLine("**Report file**: please attach ${savedAs ?: "${report.name}.zip"} (DS Forever saved it on the device; nothing was uploaded).")
        }
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return "https://github.com/$REPO/issues/new?title=${enc(title)}&body=${enc(body)}"
    }

    /** what Send report says afterwards: the file, and whether GitHub opened (no browser: where to find the file) */
    fun sentMessage(path: String, browserOpened: Boolean): String =
        if (browserOpened) "Saved: $path\nGitHub's new-issue page is open: attach that file there and submit."
        else "Saved: $path\nNo browser opened: attach that file to an issue at github.com/$REPO/issues, or send it to the developer."

    fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(ms))

    // ------------------------------------------------------------------ on the device

    /** where a player finds it: Documents/DS Forever/reports with all-files access, else the app's own folder */
    fun reportsDir(ctx: Context): File = publicOr(ctx, "reports")

    /** the crash reports CrashLogs (the launcher) keeps: the same folder rule */
    fun crashDir(ctx: Context): File = publicOr(ctx, "logs")

    private fun publicOr(ctx: Context, sub: String): File {
        val shared = runCatching {
            if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "DS Forever/$sub")
            else null
        }.getOrNull()
        return shared ?: File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, sub)
    }

    /** the report made now, from this device (off the main thread: it reads the logcat and files) */
    fun collect(ctx: Context, description: String, now: Long = System.currentTimeMillis()): Report {
        DiagLog.init(ctx)
        val crashes = (crashDir(ctx).listFiles()?.toList().orEmpty() +
            File(ctx.getExternalFilesDir(null), "crash_logs").listFiles()?.toList().orEmpty())
            .filter { it.isFile && it.name.endsWith(".txt") && now - it.lastModified() < 7L * 24 * 3600_000 }
            .sortedByDescending { it.lastModified() }.take(MAX_CRASHES)
            .map { it.name to runCatching { it.readText().take(200_000) }.getOrDefault("") }
        return build(now, description, facts(ctx), DiagLog.lines(WINDOW_MS, now), logcat(now - WINDOW_MS), crashes,
            DiagLog.recording(now) || DiagLog.lines(WINDOW_MS, now).any { it.contains("report: recording started") }, Redact.names())
    }

    fun facts(ctx: Context): Facts {
        val pkg = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        val app = "${pkg?.versionName ?: "?"} (${if (Build.VERSION.SDK_INT >= 28) pkg?.longVersionCode else pkg?.versionCode})"
        val addons = runCatching { ctx.assets.open("companion/manifest.txt").bufferedReader().use { it.readLine() } }.getOrNull() ?: "?"
        val game = runCatching {
            val row = GameFiles.buildRow(GameFiles.gameRoot(ctx))
            "${row?.get("Product") ?: "?"} ${row?.get("Version") ?: ""}".trim()
        }.getOrNull() ?: "?"
        val device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
        val settings = buildList {
            runCatching { FrameGen.init(ctx); add("Frame generation: ${if (FrameGen.enabled && FrameGen.hasDll()) "on ${FrameGen.multiplier}x" else "off"}${if (FrameGen.hasDll()) "" else " (no DLL)"}") }
            runCatching { Settings.init(ctx); add("Indoor maps in the background: ${if (Settings.indoorBuild) "on" else "off"}") }
            runCatching { add(IndoorSchedulerStep.readiness(ctx).label) }
            runCatching { add("Game art: ${ArtHarvest.state.javaClass.simpleName}" + ((ArtHarvest.state as? ArtHarvest.State.Failed)?.let { " (${it.why})" } ?: "")) }
            add("Game running: ${LauncherHooks.gameUp()}")
            CastDiag.line()?.let { add(it) }                    // p-bars-grey (1.0.8): what Home's bars decided at their last draw
        }
        return Facts(app, addons, game, device, settings)
    }

    /** this process's logcat since [sinceMs] (own lines need no permission) */
    private fun logcat(sinceMs: Long): List<String> = runCatching {
        val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(sinceMs))
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "-T", since, "--pid=${android.os.Process.myPid()}"))
        try { p.inputStream.bufferedReader().readLines() } finally { p.destroy() }
    }.getOrDefault(emptyList())

    /** the zip, written whole (temp + rename): the file, or null */
    fun save(ctx: Context, report: Report): File? = runCatching { writeZip(reportsDir(ctx), report) }.getOrNull()

    /** the zip, or the reason it could not be written (the notice says it) */
    fun saveOrWhy(ctx: Context, report: Report): Result<File> = runCatching {
        val dir = reportsDir(ctx)
        writeZip(dir, report).also { check(it.isFile && it.length() > 0) { "nothing was written to ${dir.path}" } }
    }

    fun writeZip(dir: File, report: Report): File {
        dir.mkdirs()
        val out = File(dir, "${report.name}.zip")
        val tmp = File(dir, "${report.name}.zip.part")
        ZipOutputStream(tmp.outputStream().buffered()).use { z ->
            for ((n, t) in report.files) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        }
        if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
        return out
    }
}
