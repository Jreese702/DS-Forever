package com.jrees.wowcompanion

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * THE REPORT'S DIAGNOSTICS (the user, after reports #8-#13 - "game art taking a very long time", "stuck on 68% EPL", "crashing on
 * launch", "dont load", "no update" - held only the player's words: "make the APP start up provide WAY more information so that you
 * can better troubleshoot when these get sent"). One text block, in sections, in every problem report (diagnostics.txt, and the
 * receiver's collapsed "Diagnostics" section): the app and add-on set, the device, the GPU profile and the container's launch, the game
 * folder (.build.info's rows, the product chosen, the exe per flavor, Data's size), storage access, the version checks, the switch, the
 * art harvest step by step, the interiors bake, the start-up stages with their times, the crashes and exits, and the last lines of our
 * own log tags.
 *
 * PRIVACY: everything goes through [sanitize] ([Redact]: logins, e-mails, BattleTags, the known character / realm / guild names, chat,
 * IP addresses, secrets in key=value form) and lines naming the login file or a credential are dropped whole. login.txt is never read.
 * SIZE: each section at most [SECTION_MAX], the block at most [MAX] ([render]).
 */
object StartupDiag {
    const val MAX = 40_000
    const val SECTION_MAX = 9_000
    const val LOG_LINES = 200

    class Section(val title: String, val lines: List<String>)

    /** our own log tags for the block's log (the start-up path's): the setup, the art, the sign-in, CASC, the launcher's own */
    val LOG_TAGS = setOf("WCSETUP", "WCLOG", "WCHARVEST", "WCLOGIN", "WCCASC", "WCREPORT", "WCUPDATE", "WCINDOOR", "WCINTERIORS", "WCRUNTIME", "DSFORK", "DSF", "DSForever",
        "WoWForeverScreen", "WowClientDownloader", "AppUpdater", "CrashLogs", "XServerScreen", "ContainerManager")

    /** a whole line that names the login file or a credential: dropped, not just masked */
    private val DROP = Regex("""(?i)login\.txt|\bpassword\b|\bpasswd\b|\bbearer\b|authorization:|x-dsf-key|github_token|refresh_token|access_token""")

    /** the privacy pass for the block: [Redact] (with the known [names]), and credential lines dropped whole */
    fun sanitize(text: String, names: Collection<String> = Redact.names()): String {
        val pattern = Redact.namePattern(names)
        return text.lineSequence().filterNot { DROP.containsMatchIn(it) }.joinToString("\n") { Redact.text(it, pattern) }
    }

    /** the block: "=== Title ===" and its lines, each section cut at [SECTION_MAX], the whole at [max] (the log, last, loses first) */
    fun render(sections: List<Section>, max: Int = MAX): String {
        val parts = sections.map { s ->
            val body = s.lines.joinToString("\n").let { if (it.length > SECTION_MAX) it.take(SECTION_MAX) + "\n[section cut]" else it }
            "=== ${s.title} ===\n" + body.ifEmpty { "(nothing)" }
        }
        val all = parts.joinToString("\n\n")
        if (all.length <= max) return all
        // over the cap: the sections in order while they fit, then what is left of the next one, and a note
        val out = StringBuilder()
        for (p in parts) {
            val room = max - out.length - 40
            if (room <= 0) break
            if (p.length + 2 <= room) out.append(p).append("\n\n") else { out.append(p.take(room)).append("\n[cut: the report's limit]"); break }
        }
        return out.toString().trimEnd()
    }

    /** the player's "what's included" line (the report page shows it under the link) */
    const val INCLUDED = "Also included: your device, the game folder's version, the art and start-up progress, and DS Forever's own " +
        "log. No logins, names or chat."

    // ------------------------------------------------------------------ on the device

    /** every section, read now (off the main thread: it lists folders and reads the log); never throws */
    fun collect(ctx: Context, now: Long = System.currentTimeMillis()): List<Section> = listOf(
        sec("App") { app(ctx, now) },
        sec("Device") { device(ctx) },
        sec("GPU and container") { container() },
        sec("Game folder") { gameFolder(ctx) },
        sec("Start-up screen") { startScreen() },
        sec("Start-up stages (time since the app started)") { StartupTrace.lines().ifEmpty { listOf("(none recorded in this run)") } },
        sec("Updates") { updates(now) },
        sec("Game art") { art(ctx, now) },
        sec("Interiors") { interiors(ctx) },
        sec("Crashes and exits") { crashes(ctx, now) },
        sec("Log (our start-up tags, newest last)") { log(now) },
    )

    /** the block made now: [collect], [render], [sanitize] */
    fun text(ctx: Context, names: Collection<String> = Redact.names()): String = sanitize(render(collect(ctx)), names)

    private fun sec(title: String, lines: () -> List<String>) =
        Section(title, runCatching(lines).getOrElse { listOf("(could not be read: ${it.javaClass.simpleName}: ${it.message})") })

    /** one line, its own failure said in its place (one unreadable fact never costs its section) */
    private fun line(f: () -> String): String = runCatching(f).getOrElse { "(a line could not be read: ${it.javaClass.simpleName}: ${it.message})" }

    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
    private fun ago(ms: Long, now: Long) = if (ms <= 0) "?" else "${stamp(ms)} (${dur(now - ms)} ago)"
    private fun dur(ms: Long): String {
        val s = ms / 1000
        return when { s < 120 -> "$s s"; s < 7200 -> "${s / 60} min"; s < 172_800 -> "${s / 3600} h"; else -> "${s / 86_400} days" }
    }
    private fun mb(b: Long) = "%.0f MB".format(Locale.US, b / 1048576.0)
    private fun gb(b: Long) = "%.1f GB".format(Locale.US, b / 1073741824.0)

    private fun app(ctx: Context, now: Long): List<String> {
        val pkg = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        val code = if (Build.VERSION.SDK_INT >= 28) pkg?.longVersionCode else @Suppress("DEPRECATION") pkg?.versionCode?.toLong()
        val addons = runCatching { ctx.assets.open("companion/manifest.txt").bufferedReader().use { it.readLine() } }.getOrNull() ?: "?"
        val up = SystemClock.elapsedRealtime() - StartupTrace.processStartMs
        return listOf(
            "DS Forever ${pkg?.versionName ?: "?"} ($code), package ${ctx.packageName}",
            "Add-on set: $addons",
            "App running for ${dur(up)}; the device up for ${dur(SystemClock.elapsedRealtime())}",
            "Installed ${ago(pkg?.firstInstallTime ?: 0, now)}; this version since ${ago(pkg?.lastUpdateTime ?: 0, now)}",
            "Report made ${stamp(now)} (${java.util.TimeZone.getDefault().id})",
        )
    }

    private fun device(ctx: Context): List<String> {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        fun vol(f: File?): String = runCatching {
            val s = StatFs((f ?: return "?").path)
            "${gb(s.availableBytes)} free of ${gb(s.totalBytes)}"
        }.getOrDefault("?")
        val game = runCatching { GameFiles.gameRoot(ctx) }.getOrNull()
        return listOfNotNull(
            line { "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, board ${Build.BOARD}, hardware ${Build.HARDWARE})" },
            if (Build.VERSION.SDK_INT >= 31) line { "SoC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" } else null,
            line { "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), security patch ${Build.VERSION.SECURITY_PATCH}, build ${Build.DISPLAY}" },
            line { "ABIs: ${Build.SUPPORTED_ABIS.joinToString()}" },
            line { "RAM: ${mb(mi.availMem)} free of ${mb(mi.totalMem)}${if (mi.lowMemory) " (LOW MEMORY now)" else ""}, low at ${mb(mi.threshold)}" },
            line { "Storage (the app's own): ${vol(ctx.filesDir)}" },
            line { "Storage (the game folder's volume): ${vol(game?.takeIf { it.exists() } ?: game?.parentFile)}" },
            line { "Storage access: all-files ${if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else "n/a (before Android 11)"}" +
                "; the start-up screen says ${SetupBridge.inputs?.storage ?: "?"}" },
        )
    }

    private fun container(): List<String> = listOf(
        "GPU profile: ${LauncherHooks.gpuLine.ifEmpty { "(not picked yet in this run)" }}",
        "Launch: ${LauncherHooks.launchState.ifEmpty { "(no launch in this run)" }}",
        "Game screen up: ${LauncherHooks.gameUp()}" + (if (LauncherHooks.gameStartSec > 0) ", since ${stamp(LauncherHooks.gameStartSec * 1000)}" else ""),
        "Last clean exit: ${LauncherHooks.lastCleanExit.ifEmpty { "(none)" }}",
    )

    private fun gameFolder(ctx: Context): List<String> {
        val root = GameFiles.gameRoot(ctx)
        val out = ArrayList<String>()
        out += "Path: ${root.path} (exists ${root.isDirectory})"
        val info = File(root, WowProduct.BUILD_INFO)
        if (!info.isFile) out += ".build.info: missing"
        else {
            val rows = WowProduct.parse(runCatching { info.readText() }.getOrDefault(""))
            out += ".build.info: ${rows.size} rows, ${info.length()} bytes, written ${stamp(info.lastModified())}"
            for (r in rows) out += "  row: product ${r["Product"] ?: "?"}, version ${r["Version"] ?: "?"}, active ${r["Active"] ?: "?"}, " +
                "branch ${r["Branch"] ?: "?"}, build key ${r["Build Key"]?.take(32) ?: "?"}"
        }
        val choice = runCatching { WowProduct.choose(root) }.getOrNull()
        out += "Chosen: ${choice?.product?.let { "${it.name} (${it.code}, ${it.flavorDir})" } ?: "none"}, its row's version ${choice?.row?.get("Version") ?: "?"}"
        out += "Switch to the launch client due: ${runCatching { WowSwitch.due(root) }.getOrDefault(false)} (launch day ${WowProduct.LAUNCH_DAY})"
        for (p in WowProduct.values()) {
            val exe = p.exe(root)
            out += "  ${p.flavorDir}: exe ${exe.name} ${if (exe.isFile) "present (${mb(exe.length())}, ${stamp(exe.lastModified())})" else "missing"}"
        }
        out += "Data: ${dataSize(File(root, "Data"))}"
        out += "Active build (GameFiles): ${runCatching { GameFiles.activeBuild(root)?.let { "${it.first} ${it.second}" } }.getOrNull() ?: "none"}"
        out += "Last open problem: ${GameFiles.lastWhy ?: "(none)"}"
        return out
    }

    /** Data's size and file count (at most 50 000 files walked) */
    private fun dataSize(dir: File): String {
        if (!dir.isDirectory) return "missing"
        var n = 0; var bytes = 0L
        val sub = HashMap<String, Long>()
        for (f in dir.walkTopDown()) {
            if (n >= 50_000) return "${gb(bytes)} in $n+ files (stopped counting)"
            if (f.isFile) { n++; bytes += f.length(); f.relativeTo(dir).path.substringBefore(File.separatorChar).let { sub[it] = (sub[it] ?: 0L) + f.length() } }
        }
        return "${gb(bytes)} in $n files (" + sub.entries.sortedByDescending { it.value }.take(6).joinToString { "${it.key} ${mb(it.value)}" } + ")"
    }

    private fun startScreen(): List<String> {
        val i = SetupBridge.inputs ?: return listOf("(the start-up screen published nothing in this run)")
        return listOf(
            "Folder found ${i.folderFound}, wrong game ${i.wrongGame}, product ${i.product.ifEmpty { "?" }}, storage ${i.storage}",
            "Login saved ${i.loginSaved}, skipped ${i.loginSkipped}, sign-in ${i.signIn}, restart offered ${i.restartOffered}, restarting ${i.restarting}",
            "Art ${i.art} ${i.artPercent}%${if (i.artWhat.isNotBlank()) " (${i.artWhat})" else ""}${if (i.artWhy.isNotBlank()) ", why: ${i.artWhy}" else ""}",
            "Add-ons installed ${i.addonsInstalled}; dependencies: ${i.dependencies ?: "(not checked)"}",
            "Interiors: ${i.indoor ?: "(no step)"}; icons: ${i.iconsLine ?: "?"}",
            "Client: ${i.client ?: "(not checked yet)"}; newer game build: ${i.gameOutdated ?: "none"}; stale data: ${i.staleData ?: "no"}",
            "App: ${i.appLine ?: "?"}; update offered: ${i.appUpdate ?: "none"}",
            "Busy: ${i.busy ?: "no"}; game running ${i.gameRunning}; installed on external storage ${i.installedExternal}",
            "Game closed note: ${i.gameClosed ?: "none"}; saved report: ${i.reportSaved ?: "none"}",
            "The last stage reached: ${StartupTrace.last() ?: "?"}",
        )
    }

    private fun updates(now: Long): List<String> = listOf(
        "App update check: " + (AppUpdate.lastCheck?.let { (c, at) -> "$c at ${ago(at, now)}" } ?: "(not run in this process)"),
        "Top screen's line: ${SetupBridge.inputs?.appLine ?: "?"}",
        "Game version check: ${SetupBridge.inputs?.client ?: "(not checked yet)"}" +
            (SetupBridge.inputs?.gameOutdated?.let { "; newer build $it" } ?: ""),
    )

    private fun art(ctx: Context, now: Long): List<String> {
        val out = ArrayList<String>()
        val st = ArtHarvest.state
        out += "State: " + when (st) {
            is ArtHarvest.State.Running -> "running ${(st.fraction * 100).toInt()}% (${st.what}), before Play ${st.beforePlay}"
            is ArtHarvest.State.Failed -> "FAILED: ${st.why} (before Play ${st.beforePlay})"
            else -> st.javaClass.simpleName
        }
        out += "Current step: ${ArtHarvest.currentStep ?: "(none)"}"
        out += "Last failure: " + (ArtHarvest.lastFailure?.let { (why, at) -> "$why at ${ago(at, now)}" } ?: "(none)")
        val stamp = runCatching { ArtHarvest.stampOf(ctx) }.getOrNull()
        val build = runCatching { GameFiles.activeBuild(GameFiles.gameRoot(ctx))?.second }.getOrNull()
        val pending = runCatching { ArtHarvest.pending(ctx).map { it.id }.toSet() }.getOrDefault(emptySet())
        out += "The folder's build: ${build ?: "none"}"
        for (s in ArtHarvest.STEPS) {
            val o = stamp?.optJSONObject(s.id)
            val done = o?.let { "stamped v${it.optInt("v")} for ${it.optString("build").take(32).let { b -> if (b == build) "this build" else b }} at ${ago(it.optLong("at"), now)}" } ?: "never stamped"
            out += "  ${s.id} v${s.version} (${if (s.beforePlay) "before Play" else "background"}${if (s.detached) ", detached" else ""}): " +
                "${if (build == null) "no build" else if (s.id in pending) "PENDING" else "done"}; $done" + (ArtHarvest.stepLog[s.id]?.let { "; this run: $it" } ?: "")
        }
        // the resume lists (issue #11: a step reopened at another %): how much of each step is kept for this build
        runCatching { File(ArtHarvest.root(ctx), ".resume").listFiles()?.sortedBy { it.name }?.forEach { f ->
            out += "  resume ${f.name}: ${runCatching { f.readLines().size }.getOrDefault(0)} parts, ${ago(f.lastModified(), now)}"
        } }
        return out
    }

    private fun interiors(ctx: Context): List<String> = listOf(
        "Readiness: ${runCatching { IndoorSchedulerStep.readiness(ctx).let { "${it.label} (done ${it.done})" } }.getOrDefault("?")}",
        "Status: ${runCatching { IndoorSchedulerStep.status(ctx)?.toString()?.take(600) }.getOrNull() ?: "(none)"}",
        "Indoor maps in the background: ${runCatching { Settings.init(ctx); Settings.indoorBuild }.getOrDefault("?")}",
    )

    private fun crashes(ctx: Context, now: Long): List<String> {
        val out = ArrayList<String>()
        val files = (ProblemReport.crashDir(ctx).listFiles()?.toList().orEmpty() +
            File(ctx.getExternalFilesDir(null), "crash_logs").listFiles()?.toList().orEmpty())
            .filter { it.isFile && it.name.endsWith(".txt") }.sortedByDescending { it.lastModified() }
        out += "DS Forever crash files: ${files.size}" + (files.firstOrNull()?.let { ", the newest ${it.name} at ${ago(it.lastModified(), now)}" } ?: "")
        files.firstOrNull()?.let { f -> runCatching { f.bufferedReader().use { r -> r.lineSequence().take(30).forEach { out += "  | $it" } } } }
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exits = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 6)
            out += "Exits Android recorded (newest first): ${exits.size}"
            for (e in exits) out += "  ${ago(e.timestamp, now)}: ${exitReason(e.reason)} (status ${e.status}, importance ${e.importance}, " +
                "process ${e.processName.substringAfter(ctx.packageName).ifEmpty { "main" }}, pss ${mb(e.pss * 1024)})" +
                (e.description?.let { ": ${it.take(160)}" } ?: "")
        }
        return out
    }

    private fun exitReason(r: Int): String = when (r) {
        1 -> "EXIT_SELF"; 2 -> "SIGNALED"; 3 -> "LOW_MEMORY"; 4 -> "CRASH"; 5 -> "CRASH_NATIVE"; 6 -> "ANR"; 7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"; 9 -> "EXCESSIVE_RESOURCE_USAGE"; 10 -> "USER_REQUESTED"; 11 -> "USER_STOPPED"; 12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"; 14 -> "FREEZER"; 15 -> "PACKAGE_STATE_CHANGE"; 16 -> "PACKAGE_UPDATED"; else -> "UNKNOWN($r)"
    }

    /** the last [LOG_LINES] lines of [LOG_TAGS] since the app started (or the last hour) */
    private fun log(now: Long): List<String> {
        val since = now - minOf(SystemClock.elapsedRealtime() - StartupTrace.processStartMs, 3_600_000L)
        return ProblemReport.logcat(since).filter { l -> ProblemReport.tagOf(l)?.let { it in LOG_TAGS } == true }.takeLast(LOG_LINES)
            .ifEmpty { listOf("(no lines)") }
    }
}
