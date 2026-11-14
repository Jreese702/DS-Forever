package com.jrees.wowcompanion

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * THE ADD-ON SETUP INSIDE THE LAUNCHER (S5, 2026-10-08; the user: "(a) Become 'DS Forever'"). The app did this with companion_setup.sh
 * through root; inside the one app there is no root and none is needed: the game folder is on shared storage (All-files access). The
 * launcher calls [run] before every launch (its prepareLaunch), with the game not running. The same rules as the script:
 *  - the add-on set ships in the app's assets (companion/manifest.txt + companion/addons/...);
 *  - it installs when a folder is MISSING, or updates when the game's set stamp is OLDER than the app's and files differ; an install
 *    whose files differ but carry no older stamp is left alone (the owners push ahead of the app between builds);
 *  - Config.wtf: the required keys (developerLog, InputDeviceInterfaceStyle = 1) and the defaults only where the game has none.
 *  - NO container edits: the launcher's X input needs no touch mode.
 *
 * INSTALL ONCE, VERIFY, NEVER LOOP (the user relaying a player, 2026-11-13: "they let everything install and it keeps reinstalling, and
 * the buttons and map show 'waiting for game' and are blank"). Up to 1.0.32 a failure after the copy began (no room on a full device,
 * a file that did not read back, the stamp not written) "restored" by DELETING every DS Forever folder and copying the backup back: on
 * a first install there was no backup, so the game was left with NO add-ons - and the next Play found them missing and did it all
 * again, every start, with the bottom screen waiting for add-ons that never loaded. Now ([install], pure java.io):
 *  - room first: not enough free space = nothing touched, the reason said;
 *  - every new file is written beside its old one ("<name>.dsfnew"), read back and checked against the set's md5 BEFORE anything is
 *    replaced: a failure removes only the new copies, the working set stays as it was;
 *  - then the swap (rename over), the set's leftovers go, the whole set is checked again, the stamp written AND read back;
 *  - the outcome is kept ([STATE] in the app's own storage): a set this app already installed and verified that is missing or changed
 *    at a later start was removed by something else - said ("removed since ..."), counted, and named as the cause after the second time;
 *  - [problem] is the one terse line for the launcher's Add-ons row, Home's add-ons notice and the problem report.
 * And DS Forever's own add-ons switched off in the game's AddOns list go back on ([reenable], the user's pick 2026-11-14).
 * Returns one line for the launcher's status / log.
 */
object LauncherSetup {
    private const val TAG = "WCSETUP"
    private const val STAMP = ".companion_set"
    internal const val NEW = ".dsfnew"
    /** the outcome of the last runs (the app's own files dir): set, ok, why, at, the re-install count */
    const val STATE = "addon_setup.json"
    /** free room kept beyond the set's own size */
    internal const val MARGIN = 2L * 1024 * 1024
    private val REQ = listOf("developerLog", "InputDeviceInterfaceStyle")
    private val DEFAULTS = listOf("GamepadDisableTooltips" to "1", "GamePadTurnWithCamera" to "1")
    /**
     * THE FRAME LIMIT ON AT 40 FPS on a fresh install (the user 2026-10-09: "the game native should have frame limit on with 40 FPS by
     * default when installed"): the client's own pair (useMaxFPS + maxFPS, names from its strings), written only when NEITHER is in
     * Config.wtf, so a choice made in the game's options (the cap off, another number) is never overwritten. Local to the device's
     * Config.wtf. The background limit (useMaxFPSBk / maxFPSBk) stays the game's own default.
     */
    private val FPS_PAIR = listOf("useMaxFPS" to "1", "maxFPS" to "40")

    /** the last run's outcome in this process (null = not run since the app started: [problem] reads the kept state) */
    @Volatile var last: Outcome? = null

    /**
     * [ok] the game's AddOns hold the app's set now (verified); [line] the log / status line; [why] the terse reason when not ([problem]);
     * [action] "none" / "install" / "update"; [again] how many times in a row a set this app had verified was found removed or changed.
     */
    data class Outcome(val ok: Boolean, val line: String, val why: String? = null, val action: String = "none", val again: Int = 0)

    fun run(ctx: Context, flavorRoot: File): String {
        val line = try { work(ctx, flavorRoot) } catch (t: Throwable) {
            Log.w(TAG, "launcher setup: ${t.message}")
            val o = Outcome(false, "DS Forever setup failed: ${t.message}", "Add-ons not installed: ${reason(t)}")
            last = o; runCatching { keep(File(ctx.filesDir, STATE), o, null, System.currentTimeMillis()) }
            o.line
        }
        // the user's pick (2026-11-14): DS Forever's own add-ons switched off in the game's AddOns list go back on, with the game stopped
        val r = runCatching { reenable(File(flavorRoot, "WTF"), { DependencyAddons.gameRunning() }) }
            .getOrElse { Reenable(skipped = "not checked (${it.javaClass.simpleName})") }
        lastReenable = r
        if (r.lines > 0 || r.failed > 0) Log.w(TAG, "AddOns.txt: ${r.line()}") else Log.i(TAG, "AddOns.txt: ${r.line()}")
        runCatching { DiagLog.event("addons", "AddOns.txt before Play: ${r.line()}") }
        runCatching { File(ctx.filesDir, REENABLE_STATE).writeText(JSONObject().put("at", System.currentTimeMillis()).put("line", r.line()).toString()) }
        return if (r.lines > 0 || r.failed > 0) "$line; AddOns.txt: ${r.short()}" else line
    }

    // ------------------------------------------------------------------ DS Forever's own add-ons switched back on (AddOns.txt)

    /**
     * DS FOREVER'S ADD-ONS SWITCHED OFF IN THE GAME'S ADDONS LIST GO BACK ON (the user's pick, 2026-11-14: "When the app finds DS Forever's
     * own add-ons switched off in the game's AddOns list: switch them back on"). The game keeps what its AddOns list switched off in
     * AddOns.txt, one per character (WTF/Account/<account>/<realm>/<character>/AddOns.txt) and, when the client keeps one, one for the
     * account (WTF/Account/<account>/AddOns.txt), a line per add-on: "<folder>: enabled" / "<folder>: disabled". A "has been blocked"
     * pop-up's Disable (a Disable + ReloadUI) or an untick there leaves ours "disabled": the game then loads none of them, TapPad's own
     * switch-back-on (pad20) never runs, and the bottom screen waits for add-ons that never speak.
     * Before Play, with the game stopped ([run], from the launcher's prepareLaunch):
     *  - ONLY our own lines change ("WowCompanion<letters>" at the line's start, as each of our folders is named, the case exact),
     *    "disabled" to "enabled"; every
     *    other line - another add-on's, ours already on, anything not in that form - stays byte for byte, the line endings and a BOM too;
     *  - a file without one of ours switched off is not written at all; one that is: written beside it ([NEW]), read back, then renamed
     *    over it (a stop part-way leaves the old file whole; the next run removes a leftover copy);
     *  - nothing when [gameRunning] says the game runs (checked before the walk and again before each write);
     *  - the account, realm and character names are never read into anything: the walk holds only File objects, the result is counts.
     * Pure java.io (dsforever's AddonsReenableTest).
     */
    fun reenable(wtf: File, gameRunning: () -> Boolean): Reenable {
        if (gameRunning()) return Reenable(skipped = "not checked: the game is running")
        val account = File(wtf, "Account")
        if (!account.isDirectory) return Reenable(skipped = "none yet (no WTF/Account)")
        var files = 0; var accountWide = 0; var off = 0; var lines = 0; var written = 0; var failed = 0
        val why = LinkedHashSet<String>()
        fun dirs(d: File): List<File> = d.listFiles()?.filter { it.isDirectory && !isLink(it) && !it.name.equals("SavedVariables", true) }.orEmpty()
        fun one(f: File, wide: Boolean) {
            File(f.path + NEW).takeIf { it.isFile }?.delete()                // a copy left by a run stopped part-way
            if (!f.isFile || isLink(f)) return
            files++; if (wide) accountWide++
            try {
                val raw = f.readBytes()
                val (text, n) = switchOn(String(raw, Charsets.ISO_8859_1))      // byte for byte: every other byte stays as it was
                if (n == 0) return
                off++
                if (gameRunning()) { failed++; why += "the game started"; return }
                val bytes = text.toByteArray(Charsets.ISO_8859_1)
                val tmp = File(f.path + NEW)
                try {
                    tmp.writeBytes(bytes)
                    check(tmp.readBytes().contentEquals(bytes)) { "did not read back" }
                    val moved = runCatching { java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING); true }.getOrElse { tmp.renameTo(f) }
                    check(moved) { "not replaced" }
                    written++; lines += n
                } finally { if (tmp.exists()) tmp.delete() }
            } catch (t: Throwable) {
                failed++; why += reasonShort(t)                               // never the message: it can carry the path (the names)
            }
        }
        for (acct in dirs(account)) {
            one(File(acct, ADDONS_TXT), wide = true)
            for (realm in dirs(acct)) for (ch in dirs(realm)) one(File(ch, ADDONS_TXT), wide = false)
        }
        return Reenable(files, accountWide, off, lines, written, failed, why.joinToString(", ").ifEmpty { null })
    }

    /**
     * [files] AddOns.txt files read ([accountWide] of them the account's own), [off] those with one of ours switched off, [lines] our
     * lines switched back on (in the files [written]), [failed] files not written ([failWhy]); [skipped] why nothing was checked. Counts
     * only.
     */
    data class Reenable(val files: Int = 0, val accountWide: Int = 0, val off: Int = 0, val lines: Int = 0, val written: Int = 0,
                        val failed: Int = 0, val failWhy: String? = null, val skipped: String? = null) {
        fun line(): String {
            skipped?.let { return it }
            val read = "$files checked" + if (accountWide > 0) " ($accountWide account-wide)" else ""
            if (off == 0) return "$read; DS Forever's add-ons on in every one"
            return "$read; DS Forever's add-ons were switched off in $off: $lines switched back on in $written" +
                if (failed > 0) ", $failed NOT written (${failWhy ?: "?"})" else ""
        }
        /** the launcher's status line: only when something was switched or failed */
        fun short(): String = if (failed > 0) "$lines of DS Forever's switched back on, $failed files not written" else "$lines of DS Forever's switched back on"
    }

    /** the file's text with every one of OUR lines that says "disabled" saying "enabled" (the line's own spacing, ending, a BOM kept),
     *  and how many changed */
    internal fun switchOn(text: String): Pair<String, Int> {
        var n = 0
        val out = StringBuilder(text.length + 16)
        var start = 0
        while (start <= text.length) {
            val nl = text.indexOf('\n', start)
            val end = if (nl < 0) text.length else nl
            val cr = end > start && text[end - 1] == '\r'
            val body = text.substring(start, if (cr) end - 1 else end)
            val m = OURS_OFF.matchEntire(body)
            if (m != null) { out.append(m.groupValues[1]).append("enabled").append(m.groupValues[2]); n++ } else out.append(body)
            if (cr) out.append('\r')
            if (nl < 0) break
            out.append('\n'); start = nl + 1
        }
        return out.toString() to n
    }

    /** one of OUR lines switched off: a BOM (as Latin-1 bytes) at the file's start, spaces, "WowCompanion<more>", the colon, "disabled" */
    private val OURS_OFF = Regex("""^((?:\u00EF\u00BB\u00BF)?[ \t]*WowCompanion[A-Za-z0-9_]*[ \t]*:[ \t]*)disabled([ \t]*)$""")
    private const val ADDONS_TXT = "AddOns.txt"
    /** the last before-Play re-enable's counts (the report's line: [reportLine]) */
    const val REENABLE_STATE = "addons_txt.json"
    @Volatile var lastReenable: Reenable? = null

    private fun isLink(f: File) = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(true)
    private fun reasonShort(t: Throwable): String {
        val m = generateSequence(t) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return when {
            m.contains("ENOSPC", true) || m.contains("No space", true) -> "no room"
            m.contains("EACCES", true) || m.contains("Permission denied", true) || m.contains("EPERM", true) -> "no permission"
            m.contains("EROFS", true) || m.contains("Read-only", true) -> "read-only"
            else -> t.javaClass.simpleName
        }
    }

    private fun work(ctx: Context, root: File): String {
        val manifest = ctx.assets.open("companion/manifest.txt").use { it.readBytes().toString(Charsets.UTF_8) }.lines()
        val appSet = manifest.firstOrNull { it.startsWith("set ") }?.removePrefix("set ")?.trim()?.toIntOrNull()
        if (appSet != null) com.jrees.wowcompanion.stream.BridgeProtocol.addonSet = appSet   // told to Desktop on hello (its update check)
        val want = manifest.filter { it.length > 34 && !it.startsWith("set ") }.map { it.substring(0, 32) to it.substring(34).trim() }
        val state = File(ctx.filesDir, STATE)
        val o = install(File(root, "Interface/AddOns"), want, appSet, state, System.currentTimeMillis(),
            backup = { File("/sdcard/WowCompanion/setup/launcher-${System.currentTimeMillis()}") }) { rel ->
            ctx.assets.open("companion/addons/$rel").use { it.readBytes() }
        }
        last = o
        val line = o.line + "; " + config(File(root, "WTF/Config.wtf"))
        if (o.ok) Log.i(TAG, line) else Log.w(TAG, line)
        return line
    }

    /**
     * The install itself (pure java.io: dsforever's PauseDupTest runs it). [addons] the game's Interface/AddOns; [want] (md5,
     * relative path) per file; [read] a file of the app's set; [state] the kept outcome; [backup] where the old folders are copied
     * before an UPDATE (best effort: the old set is never deleted before the new one is whole, so a backup that fails stops nothing).
     */
    fun install(addons: File, want: List<Pair<String, String>>, appSet: Int?, state: File, now: Long,
                backup: (() -> File)? = null, read: (String) -> ByteArray): Outcome {
        val folders = want.map { it.second.substringBefore('/') }.toSet()
        // a run stopped part-way (the app killed mid-copy) left new copies beside the old files: never read as the set, gone now
        for (f in folders) File(addons, f).takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && it.name.endsWith(NEW) }?.forEach { it.delete() }
        // a folder is MISSING without its .toc too (an empty folder left by anything: the game loads nothing from it)
        val tocs = want.map { it.second }.filter { it.endsWith(".toc", ignoreCase = true) }.groupBy { it.substringBefore('/') }
        val missing = folders.filter { f -> !File(addons, f).isDirectory || tocs[f].orEmpty().any { !File(addons, it).isFile } }
        val stamp = readStamp(addons)
        val differ = want.filter { (md5, rel) -> md5Of(File(addons, rel)) != md5 }
        val update = appSet != null && stamp != null && stamp < appSet && differ.isNotEmpty()
        val kept = runCatching { JSONObject(state.readText()) }.getOrNull()
        if (missing.isEmpty() && !update) {
            val o = Outcome(true, "DS Forever add-ons: set ${stamp ?: "none"}, app set $appSet, ${differ.size} differ")
            // a verified set still in place: the re-install count starts again (a failure before stays the problem until it is whole)
            if (kept == null || kept.optInt("set") != appSet || !kept.optBoolean("ok") || kept.optInt("again") != 0) keep(state, o, appSet, now, kept)
            return o
        }
        val action = if (missing.isNotEmpty()) "install" else "update"
        // THE LOOP'S OWN EVIDENCE: this app installed and verified THIS set before, and it is missing / changed again now
        val gone = kept != null && kept.optBoolean("ok") && kept.optInt("set") == appSet && appSet != null && kept.optLong("at") > 0
        val again = if (gone) kept!!.optInt("again") + 1 else 0
        val since = if (gone) " (installed and checked ${GameLife.minutes((now - kept!!.optLong("at")).coerceAtLeast(0L))} ago; " +
            "${if (missing.isNotEmpty()) "missing now: ${missing.joinToString()}" else "${differ.size} files changed"}: removed by something else, time $again)" else ""
        fun fail(why: String, line: String, touched: Boolean = false): Outcome {
            val o = Outcome(false, "DS Forever add-ons NOT ${if (action == "install") "installed" else "updated"} ($line)$since; " +
                (if (touched) "tried again at the next Play" else "the files in the game folder were not touched"), why, action, again)
            keep(state, o, appSet, now, kept); return o
        }
        // 1. the app's own files, checked against its own manifest (an APK whose set does not match: nothing goes in)
        val bytes = HashMap<String, ByteArray>()
        for ((md5, rel) in want) {
            val b = runCatching { read(rel) }.getOrElse { return fail("Add-ons not installed: the app's copy is damaged. Reinstall DS Forever.", "the app's $rel: ${it.message}") }
            if (md5(b) != md5) return fail("Add-ons not installed: the app's copy is damaged. Reinstall DS Forever.", "the app's $rel does not match its set")
            bytes[rel] = b
        }
        // 2. room: the new copies sit beside the old ones until the swap
        val toWrite = want.filter { (md5, rel) -> md5Of(File(addons, rel)) != md5 }
        val need = toWrite.sumOf { bytes.getValue(it.second).size.toLong() } + MARGIN
        addons.mkdirs()
        if (!addons.isDirectory) return fail("Add-ons not installed: the game folder can't be written.", "no AddOns folder: ${addons.path} could not be made")
        val free = runCatching { addons.usableSpace }.getOrDefault(-1L)
        if (free in 1 until need) return fail("Add-ons not installed: needs ${mb(need)} free (${mb(free)} free).", "needs ${mb(need)} free, ${mb(free)} free")
        // 3. the old set kept aside before an update (best effort)
        if (action == "update" && backup != null) runCatching {
            val bak = backup().apply { mkdirs() }
            for (f in folders) File(addons, f).takeIf { it.isDirectory }?.copyRecursively(File(bak, "addons/$f"), overwrite = true)
        }
        // 4. every new file beside the old one, read back and checked, BEFORE anything is replaced
        val staged = ArrayList<Pair<File, File>>()
        // a failure: only the new copies go, and a folder this run made that holds nothing else (never an empty folder left behind:
        // it would read as installed at the next start)
        fun undo() {
            for ((tmp, _) in staged) tmp.delete()
            for (f in missing) File(addons, f).takeIf { d -> d.isDirectory && d.walkTopDown().none { it.isFile } }?.deleteRecursively()
        }
        try {
            for ((md5, rel) in toWrite) {
                val to = File(addons, rel); to.parentFile?.mkdirs()
                val tmp = File(to.path + NEW)
                staged += tmp to to                                         // before the write: a part-written copy goes too
                tmp.writeBytes(bytes.getValue(rel))
                check(md5Of(tmp) == md5) { "$rel did not read back as written" }
            }
        } catch (t: Throwable) {
            undo()
            return fail("Add-ons not installed: ${reason(t)}.", "writing: ${t.message}")
        }
        // 5. the swap; the set's leftovers go (inside its own folders only)
        for ((tmp, to) in staged) {
            if (!tmp.renameTo(to) && !(to.delete() && tmp.renameTo(to))) {
                undo()
                return fail("Add-ons not installed: a file in the game folder could not be replaced.", "replacing ${to.relativeTo(addons).path}", touched = true)
            }
        }
        val keep = want.map { it.second }.toSet()
        for (f in folders) File(addons, f).walkTopDown().filter { it.isFile && it.name != STAMP }.forEach { file ->
            if (file.relativeTo(addons).path.replace('\\', '/') !in keep) file.delete()
        }
        // 6. the whole set checked again, the stamp written and read back
        val bad = want.count { (md5, rel) -> md5Of(File(addons, rel)) != md5 }
        if (bad > 0) return fail("Add-ons not installed: $bad files did not stay as written.", "$bad files do not match the set after the copy", touched = true)
        if (appSet != null) {
            runCatching { File(addons, "WowCompanion/$STAMP").writeText("$appSet\n") }
            if (readStamp(addons) != appSet) return fail("Add-ons not installed: the game folder can't be written.", "the set stamp did not read back", touched = true)
        }
        val o = Outcome(true, "DS Forever add-ons ${if (action == "install") "installed" else "updated"}: set $appSet, ${want.size} files " +
            "(${toWrite.size} written, checked)$since", if (again >= 2) "Add-ons keep disappearing from the game folder." else null, action, again)
        keep(state, o, appSet, now, kept)
        return o
    }

    /**
     * The one terse line while the add-ons are not right, from this run ([last]) or the kept state ([STATE]): an install that failed,
     * or a set removed again and again (the loop). Null = fine / not known.
     */
    fun problem(ctx: Context): String? {
        last?.let { return it.why }
        return runCatching { problemOf(JSONObject(File(ctx.filesDir, STATE).readText())) }.getOrNull()
    }
    internal fun problemOf(kept: JSONObject?): String? = kept?.optString("why", "")?.ifEmpty { null }

    /** the report's line: the kept outcome in words */
    fun reportLine(ctx: Context): String {
        val l = last
        if (l != null) return "add-on setup: ${if (l.ok) "ok" else "FAILED"} (${l.action}${if (l.again > 0) ", removed again ${l.again}x" else ""}): ${l.line}" +
            "; AddOns.txt: ${lastReenable?.line() ?: "?"}"
        val kept = runCatching { JSONObject(File(ctx.filesDir, STATE).readText()) }.getOrNull() ?: return "add-on setup: not run yet"
        return "add-on setup (an earlier start): ${if (kept.optBoolean("ok")) "ok" else "FAILED"}: ${kept.optString("line")}; AddOns.txt: " +
            (runCatching { JSONObject(File(ctx.filesDir, REENABLE_STATE).readText()).optString("line") }.getOrNull()?.ifEmpty { null } ?: "?")
    }

    private fun keep(state: File, o: Outcome, appSet: Int?, now: Long, kept: JSONObject? = null) {
        runCatching {
            val j = JSONObject().put("set", appSet ?: 0).put("ok", o.ok).put("line", o.line).put("action", o.action).put("again", o.again)
            o.why?.let { j.put("why", it) }
            // [at] = when a set was last verified in place by this app (a failure keeps the earlier one: the loop's clock)
            j.put("at", if (o.ok) now else kept?.optLong("at") ?: 0L)
            state.parentFile?.mkdirs(); state.writeText(j.toString())
        }
    }

    private fun readStamp(addons: File): Int? = runCatching { File(addons, "WowCompanion/$STAMP").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() }.getOrNull()

    private fun mb(b: Long) = "${(b + 1024 * 1024 - 1) / (1024 * 1024)} MB"

    /** a failure in plain words (no space, no permission, else the message) */
    internal fun reason(t: Throwable): String {
        val m = generateSequence(t) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return when {
            m.contains("ENOSPC", true) || m.contains("No space", true) -> "no room left on the storage"
            m.contains("EACCES", true) || m.contains("Permission denied", true) || m.contains("EPERM", true) -> "no permission to write the game folder"
            m.contains("EROFS", true) || m.contains("Read-only", true) -> "the game folder is read-only"
            else -> t.message ?: t.javaClass.simpleName
        }
    }

    /** the required keys set to 1 and the defaults where missing; the file's own line ending kept (the game writes CRLF) */
    private fun config(f: File): String {
        if (!f.isFile) return "no Config.wtf yet"
        val raw = f.readText()
        val nl = if (raw.contains("\r\n")) "\r\n" else "\n"
        val lines = raw.split(nl).filter { it.isNotEmpty() }.toMutableList()
        fun value(k: String) = lines.firstOrNull { it.startsWith("SET $k ") }?.substringAfter("\"")?.substringBeforeLast("\"")
        var n = 0
        for (k in REQ) if (value(k) != "1") { lines.removeAll { it.startsWith("SET $k ") }; lines += "SET $k \"1\""; n++ }
        for ((k, v) in DEFAULTS) if (value(k) == null) { lines += "SET $k \"$v\""; n++ }
        if (FPS_PAIR.none { (k, _) -> lines.any { it.startsWith("SET $k ", ignoreCase = true) } })
            for ((k, v) in FPS_PAIR) { lines += "SET $k \"$v\""; n++ }
        if (n > 0) f.writeText(lines.joinToString(nl) + nl)
        return "Config.wtf: $n set"
    }

    private fun md5(b: ByteArray): String = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
    private fun md5Of(f: File): String? = if (!f.isFile) null else runCatching { md5(f.readBytes()) }.getOrNull()
}
