package com.jrees.wowcompanion

import android.content.Context
import java.io.File

/**
 * REPAIR GAME FILES (#577, 1.0.37: after an unclean stop the game folder's own storage was damaged, every open failed with CascLib error
 * 1000, and there was no way back short of reinstalling). The start screen's Repair, with the game closed, does in this order:
 * 1. the launcher's part first (WowClientDownloader.repairFiles: the build's configs, the client's own files and the CDN archive indexes,
 *    each checked against its checksum and only a damaged or missing one downloaded again), then
 * 2. [run]: the game folder's storage (Data/data) read back and checked file by file ([CascLocal.scan]); each damaged file's body
 *    downloaded again from Blizzard's CDN by its encoding key and written over the damaged one where it sits ([CascLocal.writeBack]).
 *    The same for both ways of installing: Install directly's folder and a folder copied from a PC hold the same storage.
 * 3. the app's own download cache dropped ([GameFiles.afterRepair]) and the game art tried again.
 * What could not be mended is said plainly, by how the folder was installed ([summary]). Never while the game runs; nothing is deleted.
 */
object GameRepair {
    /**
     * more damaged files than [MAX_FILES], or more than [MIN_FOR_SHARE] that are over [MAX_SHARE] of all, are not mended one by one: the
     * folder is better installed again (and so much "damage" may also be a storage layout this check misreads: nothing is written then)
     */
    const val MAX_FILES = 2_000
    const val MAX_SHARE = 0.2
    const val MIN_FOR_SHARE = 100
    fun tooMuch(damaged: Int, entries: Int): Boolean =
        damaged > MAX_FILES || (damaged > MIN_FOR_SHARE && damaged > entries * MAX_SHARE)

    data class Result(val checked: Int, val damaged: Int, val mended: Int, val left: List<String>, val problems: List<String>,
                      val route: CascHeal.Route, val tooMuch: Boolean = false, val stopped: Boolean = false) {
        val whole: Boolean get() = !stopped && left.isEmpty() && problems.isEmpty() && !tooMuch
    }

    /** the report's line for the last repair in this process (null = none) */
    @Volatile var last: String? = null
        internal set

    /** a repair is running now (the start screen's Play waits) */
    @Volatile var running = false
        private set

    /**
     * The game folder's storage checked and its damaged files mended ([CascLocal]); [onStatus] the start screen's line; [stop] asked
     * between files. [fetch] = the CDN's body for a whole encoding key (GameFiles.fetchByKey; the tests give their own).
     */
    fun run(ctx: Context, onStatus: (String) -> Unit, stop: () -> Boolean = { false }): Result {
        val root = GameFiles.gameRoot(ctx)
        // the app's cache dropped whatever happened (a repair that failed part-way may have written some files back already)
        try { return runOn(root, onStatus, stop) { key -> GameFiles.fetchByKey(ctx, key) } }
        finally { runCatching { GameFiles.afterRepair(ctx) } }
    }

    /**
     * #577 review: the repair itself failed (out of memory on a big folder, an unreadable segment, ...): said on the start screen, and
     * the report's line, instead of a crash or a Repair that never ends
     */
    const val NO_MEMORY_LINE = "Repair could not run: not enough memory. Close other apps, then Repair again."
    fun couldNotRun(t: Throwable): String {
        val why = if (t is OutOfMemoryError) "not enough memory" else (t.message ?: t.javaClass.simpleName)
        last = "could not run: $why"
        log("could not run: $why")
        return if (t is OutOfMemoryError) NO_MEMORY_LINE else "Repair could not run: $why"
    }

    internal fun runOn(root: File, onStatus: (String) -> Unit, stop: () -> Boolean, fetch: (String) -> File?): Result {
        running = true
        try {
            val route = CascHeal.route(root)
            val data = File(root, "Data/data")
            onStatus(checkLine(0))
            val scan = CascLocal.scan(data, progress = { done, total -> onStatus(checkLine(if (total > 0) (done * 100 / total).toInt() else 0)) }, stop = stop)
            log("check: ${scan.entries} files (${scan.bytes / 1_000_000} MB) read, ${scan.damaged.size} damaged, ${scan.skipped} index entries not files" +
                (if (scan.problems.isEmpty()) "" else "; ${scan.problems.joinToString("; ")}") + if (scan.stopped) "; stopped" else "")
            for (d in scan.damaged.take(20)) log("damaged: data.%03d @%d (%d bytes): %s".format(d.entry.archive, d.entry.offset, d.entry.size, d.why))
            if (scan.stopped) return done(Result(scan.entries, scan.damaged.size, 0, emptyList(), scan.problems, route, stopped = true))
            if (tooMuch(scan.damaged.size, scan.entries))
                return done(Result(scan.entries, scan.damaged.size, 0, scan.damaged.map { it.why }, scan.problems, route, tooMuch = true))
            if (scan.damaged.isEmpty()) return done(Result(scan.entries, 0, 0, emptyList(), scan.problems, route))
            // each damaged file's whole key: its own header, the build's tables, else the CDN's archive indexes in Data/indices
            val keys = HashMap<String, String>()
            for (d in scan.damaged) d.fullKey?.let { keys[d.entry.key9] = it }
            buildConfig(root)?.let { cfg -> for (k in CascLocal.configKeys(cfg)) { val p = k.take(18); if (scan.damaged.any { it.entry.key9 == p }) keys[p] = k } }
            val unknown = scan.damaged.map { it.entry.key9 }.filter { it !in keys }.toSet()
            if (unknown.isNotEmpty()) {
                onStatus("Repairing the game's files: finding ${unknown.size} files")
                keys.putAll(CascLocal.keysFromIndexes(File(root, "Data/indices"), unknown, stop))
            }
            var mended = 0
            val left = ArrayList<String>()
            for ((i, d) in scan.damaged.withIndex()) {
                if (stop()) return done(Result(scan.entries, scan.damaged.size, mended, left, scan.problems, route, stopped = true))
                onStatus(repairLine(i + 1, scan.damaged.size))
                val key = keys[d.entry.key9]
                if (key == null) { left.add("${d.entry.key9}: its key is not known"); continue }
                val body = runCatching { fetch(key) }.getOrNull()
                if (body == null) { left.add("$key: not on the CDN (${GameFiles.lastFetchWhy ?: "no answer"})"); continue }
                val why = runCatching { CascLocal.writeBack(data, d.entry, body, key) }.getOrElse { "the write failed (${it.message})" }
                runCatching { body.delete() }
                if (why == null) { mended++; log("mended $key (data.%03d @%d)".format(d.entry.archive, d.entry.offset)) }
                else { left.add("$key: $why"); log("not mended $key: $why") }
            }
            return done(Result(scan.entries, scan.damaged.size, mended, left, scan.problems, route))
        } finally { running = false }
    }

    private fun done(r: Result): Result {
        last = "checked ${r.checked} files, ${r.damaged} damaged, ${r.mended} mended" +
            (if (r.left.isEmpty()) "" else ", ${r.left.size} not: ${r.left.take(3).joinToString("; ")}") +
            (if (r.problems.isEmpty()) "" else "; ${r.problems.joinToString("; ")}") +
            (if (r.tooMuch) "; too many to mend one by one" else "") + (if (r.stopped) "; stopped" else "")
        log("done: $last")
        return r
    }

    /** the build config this folder runs (Data/config/xx/yy/<Build Key>), or null */
    private fun buildConfig(root: File): String? = runCatching {
        val key = GameFiles.activeBuild(root)?.second?.takeIf { it.length == 32 } ?: return null
        File(root, "Data/config/${key.take(2)}/${key.substring(2, 4)}/$key").takeIf { it.isFile }?.readText()
    }.getOrNull()

    private fun log(line: String) {
        runCatching { android.util.Log.i("WCSETUP", "repair: $line") }
        runCatching { DiagLog.event("repair", line) }
    }

    fun checkLine(pct: Int): String = "Checking the game's files: ${pct.coerceIn(0, 100)}%"
    fun repairLine(n: Int, of: Int): String = "Repairing the game's files: $n of $of"

    /** what to do when the repair could not mend everything, by how the folder was installed */
    fun againLine(route: CascHeal.Route): String = when (route) {
        CascHeal.Route.DIRECT -> "Install the game again: rename the WoW Forever folder, then Install directly"
        CascHeal.Route.FOLDER -> "Copy the Data folder again from your PC"
    }

    /**
     * The start screen's words after a repair ([r]), by how the folder was installed. [clientFixed] = the client's own files the
     * launcher's part downloaded again (-1 = that part could not run: [clientWhy]); they count as mended files.
     */
    fun summary(r: Result, clientFixed: Int, clientWhy: String? = null): String {
        val again = againLine(r.route)
        val extra = clientFixed.coerceAtLeast(0)
        return when {
            r.stopped -> "Repair stopped."
            r.tooMuch -> "Too many files are damaged (${r.damaged}) to repair one by one. $again"
            r.problems.isNotEmpty() -> "Some of the game's index files are damaged. $again"
            r.left.isNotEmpty() -> "Repaired ${r.mended + extra} of ${r.damaged + extra} damaged files. $again"
            r.damaged + extra > 0 -> "Repaired ${r.damaged + extra} damaged files."
            clientFixed < 0 && clientWhy != null -> "Repair could not check the game's own files: $clientWhy"
            else -> "No damaged files found."
        }
    }
}
