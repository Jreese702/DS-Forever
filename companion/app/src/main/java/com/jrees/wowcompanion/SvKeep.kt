package com.jrees.wowcompanion

import org.json.JSONObject
import java.io.File

/**
 * THE ADD-ON'S OWN SAVE FILES, KEPT BY THE APP (the user's pick after test22: "Write the add-on's save file"). This client writes
 * SavedVariables only on a clean exit and Android kills it, so nothing the player set (commands - the Spell Radial among them -, the
 * spell radial's slices, X / Y / A's fight spells, the In-combat overlay, the toolbar switches, binds...) ever reached the disk.
 *
 * The add-on (PadMap m51) sends each of OUR saved tables as Lua source (`svkeep`: var, n = settings count, lua) when it changes; the
 * app keeps the latest per table in Documents/DS Forever/settings/<var>.json (an uninstall keeps it; the previous one as .bak). BEFORE
 * the game starts (NativeLogin.prepare: the client is not running) it writes OUR OWN file WTF/Account/<account>/SavedVariables/
 * <AddOn>.lua from it - PER ENTRY ([fold], the fix of 2026-11-18): the game file's own value of an entry stays unless the kept one is a
 * genuine newer change of it, an empty or much thinner kept value never wins, and nothing (the add-on's skipped keys included) is ever
 * dropped. The game then loads everything itself at login. Nothing of the game's own (bars, bindings, other add-ons' files) is touched.
 * WHY (the 11-18 hunter case): before build 1489 the PC's `svkeep` documents, while streaming, REPLACED the kept copy whole, so the kept
 * copy held the PC's thin hunter (no fight spells) while the Thor's game file held the real one; the old rule ("the kept copy is newer:
 * it wins every conflict") would have written those empty fight spells over the game file at the next Local start.
 * SETTINGS FOLLOW (the user's pick 2026-11-18; build 1489: our tables only, WoW's own sync left to the game): while streaming, the PC's documents are MERGED per entry into
 * the kept copy (SvSync.fromPc; never [store]: a whole-table replace), and the kept copy reaches the PC at a stream session's start
 * (SvSync, Desktop's WowSvSync) after the Thor's own game file is folded in ([foldGameIn]). [beforeStart] records the account folder's
 * hash, the game's WTF path and the base (the table the game starts with).
 * Pure (java.io + org.json): dsforever's WowSwitchTest / SvSyncTest run it.
 */
object SvKeep {
    /** our saved tables and their add-on's file name */
    val FILES = mapOf("WowCompanionSpellsDB" to "WowCompanionSpells.lua", "WowCompanionDB" to "WowCompanion.lua")

    data class Doc(val variable: String, val n: Int, val lua: String, val savedAt: Long)

    fun parse(msg: JSONObject, now: Long): Doc? {
        val v = msg.optString("var"); val lua = msg.optString("lua")
        if (v !in FILES || !lua.startsWith("{") || !lua.endsWith("}")) return null
        return Doc(v, msg.optInt("n", 0), lua, now)
    }

    fun toJson(d: Doc): String = JSONObject().put("v", 1).put("var", d.variable).put("n", d.n).put("lua", d.lua).put("at", d.savedAt).toString()
    fun fromJson(s: String?): Doc? = try {
        val o = JSONObject(s ?: "")
        Doc(o.optString("var"), o.optInt("n", 0), o.optString("lua"), o.optLong("at", 0L)).takeIf { it.variable in FILES && it.lua.startsWith("{") }
    } catch (_: Throwable) { null }

    /**
     * Keep [new] over [old]? An EMPTY table never replaces a non-empty one until the store is confirmed ([armed]: the run's first word
     * was not empty, or the player changed a setting): an empty store at login means the game lost it, not that the player cleared it.
     */
    fun keep(old: Doc?, new: Doc, armed: Boolean): Boolean = !(new.n == 0 && (old?.n ?: 0) > 0 && !armed)

    /** the account folder the game saves into: the one under WTF/Account with the most recently written SavedVariables */
    fun savedVariablesDir(wtf: File): File? =
        wtf.resolve("Account").listFiles()?.filter { it.isDirectory && it.name != "SavedVariables" }
            ?.mapNotNull { a -> a.resolve("SavedVariables").takeIf { it.isDirectory } }
            ?.maxByOrNull { sv -> sv.listFiles()?.maxOfOrNull { it.lastModified() } ?: sv.lastModified() }

    /** the file's text, as the game writes it */
    fun fileText(d: Doc): String = "\n${d.variable} = ${d.lua}\n"

    /** the kept file of table [variable] in [keptDir] */
    fun keptFile(keptDir: File, variable: String) = File(keptDir, "$variable.json")

    /** the game file's table as the game last STARTED with it (what [beforeStart] wrote or found): [fold]'s per-entry base */
    fun gameBaseFile(keptDir: File, variable: String) = File(keptDir, "$variable.game.json")

    /** the game's WTF folder [beforeStart] last saw (a path, no account name): the stream's sync folds the Thor's game file in too */
    fun gameWtfFile(keptDir: File) = File(keptDir, "game.wtf")

    /** a kept copy made from the PC's table alone (the Thor had none): the next start trusts none of its entries over the game's */
    fun fromPcMark(keptDir: File, variable: String) = File(keptDir, "$variable.json.frompc")

    /** the game file's text when OUR write made it ([fileText]: one line, the add-on's own order); the game's own save is many lines */
    fun isOurs(text: String, variable: String, t: Map<Any, Any>): Boolean =
        runCatching { text == "\n$variable = ${SvLua.ser(t)}\n" }.getOrDefault(false)

    /** what [fold] did (counts only: an entry's key is a character name, never logged) */
    data class Fold(val table: Map<Any, Any>, val fromKept: Int, val added: Int, val refused: Int)

    /**
     * THE HARD SAFETY RULE (the user, 2026-11-18: "whatever you do please dont delete my current hunters settings on local"; the 12:29
     * stream sent the PC a thin hunter, and the next start would have put the kept copy's EMPTY fight spells over the game file's).
     * The game's own table [game] (all of it, the add-on's skipped keys too) with the kept copy [kept]'s entries in, PER ENTRY (a table's
     * own keys and, one level down, each character's store, as [SvMerge]). The game file's value stays unless the kept value is a
     * GENUINE newer change of that entry:
     *  - an entry only the kept copy has comes in; one only the game has stays (nothing is ever dropped; skipped keys stay the game's);
     *  - a kept value that is empty, or holds less than half the game's settings, never wins (a lost / reset / thin copy);
     *  - with a [base] (the table the game last started with): the kept value wins only when the kept entry changed since and the
     *    game's did not; both changed: the newer table ([keptAt] vs the game file's time [gameAt]); the kept one unchanged: the game's;
     *  - with NO base (never seen, or the kept copy came from the PC alone): the game's value wins every conflict.
     * Pure.
     */
    fun fold(game: Map<Any, Any>, gameAt: Long, kept: Map<Any, Any>, keptAt: Long, base: Map<Any, Any>?, skip: Set<String>): Fold {
        var fromKept = 0; var added = 0; var refused = 0
        fun unit(g: Any?, k: Any?, b: Any?): Any? {
            if (k == null) return g
            if (g == null) { added++; return k }
            if (SvMerge.same(g, k)) return g
            val gl = SvLua.leaves(g); val kl = SvLua.leaves(k)
            if (gl > 0 && (kl == 0 || (gl >= 4 && kl * 2 < gl))) { refused++; return g }
            if (base == null) return g
            val kChanged = b == null || !SvMerge.same(k, b)
            val gChanged = b == null || !SvMerge.same(g, b)
            val takeKept = kChanged && (!gChanged || keptAt > gameAt)
            if (takeKept) fromKept++
            return if (takeKept) k else g
        }
        val out = LinkedHashMap<Any, Any>()
        for (key in LinkedHashSet<Any>(game.keys + kept.keys)) {
            val g = game[key]; val k = kept[key]; val b = base?.get(key)
            if (key in skip) { g?.let { out[key] = it }; continue }
            val v: Any? = if (g is Map<*, *> && k is Map<*, *> && !SvMerge.isList(g) && !SvMerge.isList(k)) {
                @Suppress("UNCHECKED_CAST") val gm = g as Map<Any, Any>
                @Suppress("UNCHECKED_CAST") val km = k as Map<Any, Any>
                val bm = b as? Map<*, *>
                val sub = LinkedHashMap<Any, Any>()
                for (k2 in LinkedHashSet<Any>(gm.keys + km.keys)) unit(gm[k2], km[k2], bm?.get(k2))?.let { sub[k2] = it }
                sub
            } else unit(g, k, b)
            if (v != null) out[key] = v
        }
        return Fold(out, fromKept, added, refused)
    }

    /** the OTHER saved tables than [variable]: their kept copies in [keptDir] and their game files in [sv] (those that read) */
    fun otherTables(keptDir: File, sv: File?, variable: String): List<Map<Any, Any>> = FILES.filterKeys { it != variable }.flatMap { (v, n) ->
        listOfNotNull(
            readKept(keptDir, v)?.let { d -> runCatching { SvLua.parse(d.lua) }.getOrNull() },
            sv?.let { runCatching { File(it, n).takeIf { f -> f.isFile }?.readText()?.let { t -> SvLua.parse(t, v) } }.getOrNull() })
    }

    /** every one of our saved tables the device holds: both kept copies and both game files (the game's folder recorded at a start) */
    fun tables(keptDir: File): List<Map<Any, Any>> {
        val sv = runCatching { gameWtfFile(keptDir).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { savedVariablesDir(File(it)) } }.getOrNull()
        return otherTables(keptDir, sv, "")
    }

    private fun readKept(keptDir: File, variable: String): Doc? =
        fromJson(runCatching { keptFile(keptDir, variable).takeIf { it.isFile }?.readText() }.getOrNull())

    private fun writeDoc(f: File, d: Doc) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".part")
        tmp.writeText(toJson(d))
        if (f.exists() && !f.delete()) { tmp.delete(); error("could not replace ${f.name}") }
        check(tmp.renameTo(f)) { "could not write ${f.name}" }
    }

    /** the kept copy of [variable] set to [table] (a dated backup first: no backup, no change); keeps its time [at] */
    private fun setKept(keptDir: File, variable: String, table: Map<Any, Any>, at: Long, now: Long): Boolean {
        val f = keptFile(keptDir, variable)
        if (!SvBackup.dated(f, File(keptDir, SvBackup.DIR), now)) return false
        return runCatching { writeDoc(f, Doc(variable, SvLua.leaves(table), SvLua.ser(table), at)); true }.getOrDefault(false)
    }

    /**
     * ONE of our tables: the game's own file [target] and the kept copy folded ([fold]); the kept copy becomes the full picture (so a
     * stream's sync sends the PC everything the Thor has). [backupDir] non-null = BEFORE THE GAME STARTS: the game file is written too
     * when it changes (the old one to [backupDir], and the very first one kept for good in sync-backups), and the base recorded.
     * A game file that does not read whole is never written over (and nothing else changes). One [log] line per outcome. Pure.
     */
    fun foldOne(target: File, keptDir: File, variable: String, backupDir: File?, now: Long, log: (String) -> Unit) {
        val name = target.name
        val skip = SvLua.SKIP[variable].orEmpty()
        val kept = readKept(keptDir, variable)
        // a reset's marks (SvReset, 2026-11-19): the kept copy's entries a reset took away (its old value, or a copy older than the reset)
        // leave the fold as if the kept copy had none; the game file's the same, below
        val resets = SvReset.read(keptDir)
        val keptRaw = kept?.takeIf { it.n > 0 }?.let { runCatching { SvLua.parse(it.lua) }.getOrNull() }
        val keptT0 = keptRaw?.let { if (resets.isEmpty()) it else SvReset.strip(variable, it, resets, kept.savedAt).first }
        val mark = fromPcMark(keptDir, variable)
        // RealmFix (the 11-20 hunter): the game file read first (when it reads whole) so the orphans of both copies fold as ONE picture
        val gamePeek = runCatching { target.takeIf { it.isFile }?.readText()?.let { SvLua.parse(it, variable) } }.getOrNull()
        // the k6 review round 2 (item C): the name's realms from BOTH our tables (the other one's kept copy and game file too, its realmOf
        // with them), as the add-on's realmsOf looks at both: a name under another realm in the other table is never folded here
        val realms = RealmFix.realmsByName(listOf(gamePeek, keptT0) + otherTables(keptDir, target.parentFile, variable))
        val keptT = keptT0?.let { k ->
            // the k6 review (item 3): a store the real key held BEFORE a reset (the raw kept copy, a reset mark) is held: a Reset is
            // never undone by an orphan's old value
            val r = RealmFix.clean(k, realms, RealmFix.held(listOf(gamePeek, keptRaw)) + RealmFix.resetHeld(resets, variable))
            when {
                r.items.isEmpty() -> k
                !RealmFix.backup(keptFile(keptDir, variable), File(keptDir, "${SvBackup.DIR}/${RealmFix.DIR}"), now) -> {
                    log("settings backup: $variable: orphan keys NOT folded in the kept copy (no backup)"); k
                }
                else -> { log("settings backup: $variable kept copy: orphan keys folded (${r.adopted} into the real key, ${r.dropped} dropped)"); r.table }
            }
        }
        if (!target.isFile) {
            if (keptT == null || backupDir == null) { if (backupDir != null) log("settings backup: $name not written (nothing kept)"); return }
            log("settings backup kept before sync")
            log(runCatching { write(target, Doc(variable, SvLua.leaves(keptT), SvLua.ser(keptT), now), backupDir) }
                .onSuccess { runCatching { writeDoc(gameBaseFile(keptDir, variable), Doc(variable, 0, SvLua.ser(keptT), now)); mark.delete() } }
                .getOrElse { "settings backup: $name NOT written (${it.javaClass.simpleName})" })
            return
        }
        val text = runCatching { target.readText() }.getOrNull()
        val gameRaw = text?.let { runCatching { SvLua.parse(it, variable) }.getOrNull() }
        if (gameRaw == null) { log("settings backup: $name not written (the game's own file does not read whole: left as it is)"); return }
        val gameAt = target.lastModified()
        // SvReset: the game file's entries a reset took away (a file older than the reset, or the old value itself) are not the game's
        val (game0, resetOut) = if (resets.isEmpty()) gameRaw to 0 else SvReset.strip(variable, gameRaw, resets, gameAt)
        // RealmFix: the game file's orphans folded too (the real key wins; a store the real key holds in the kept copy is never given
        // an orphan's); before a start the game file is then written without them (its old file to backupDir, and a realm-fix copy)
        val game = RealmFix.clean(game0, realms, RealmFix.held(listOf(keptRaw, keptT0, gameRaw)) + RealmFix.resetHeld(resets, variable)).let { r ->
            when {
                r.items.isEmpty() -> game0
                backupDir != null && !RealmFix.backup(target, File(keptDir, "${SvBackup.DIR}/${RealmFix.DIR}"), now) -> {
                    log("settings backup: $name: orphan keys NOT folded (no backup)"); game0
                }
                else -> { log("settings backup: $name: orphan keys folded (${r.adopted} into the real key, ${r.dropped} dropped)"); r.table }
            }
        }
        if (resetOut > 0) log("settings backup: $name: $resetOut reset entries left out")
        if (keptT == null) {
            // nothing (or nothing usable) kept: the kept copy starts as the game's own settings; the game file is not touched (but for
            // the entries a reset took away: the file written without them before the game starts)
            val own = SvLua.withoutSkipped(variable, game)
            if (SvLua.leaves(own) > 0 && kept?.lua != SvLua.ser(own)) setKept(keptDir, variable, own, gameAt, now)
            if (backupDir != null) {
                val changed = resetOut > 0 || game !== game0                  // a reset's entries out, or RealmFix's orphans folded
                if (changed) runCatching { log(write(target, Doc(variable, SvLua.leaves(game), SvLua.ser(game), now), backupDir)) }
                    .onFailure { log("settings backup: $name NOT written (${it.javaClass.simpleName})") }
                runCatching { writeDoc(gameBaseFile(keptDir, variable), Doc(variable, 0, SvLua.ser(game), now)) }
                if (!changed) log("settings backup: $name not written (nothing kept: the game's own kept)")
            }
            return
        }
        // the base: the table the game last started with; none recorded yet (an update to this build) -> the game file itself when OUR
        // write made it (the game never saved since); a kept copy made from the PC alone -> none (the game's win every conflict)
        val recorded = runCatching { fromJson(gameBaseFile(keptDir, variable).readText())?.let { SvLua.parse(it.lua) } }.getOrNull()
        val base = when {
            mark.exists() -> null
            recorded != null -> recorded
            isOurs(text, variable, gameRaw) -> gameRaw
            else -> null
        }
        val keptAt = kept?.savedAt ?: 0L
        val f = fold(game, gameAt, keptT, keptAt, base, skip)
        val m = f.table
        val counts = "${f.fromKept} from the kept copy, ${f.added} added, ${f.refused} refused" + if (base == null) " (no base: the game's own win)" else ""
        if (backupDir != null) {
            if (!SvMerge.same(m, gameRaw)) {
                // the very first game file this build replaces stays for good (never rotated): the user's "never delete" net
                runCatching { File(keptDir, SvBackup.DIR).let { d -> d.mkdirs(); File(d, "$name.before-fold").takeIf { !it.exists() }?.let { target.copyTo(it) } } }
                log("settings backup kept before sync")
                val line = runCatching { write(target, Doc(variable, SvLua.leaves(m), SvLua.ser(m), now), backupDir) }
                    .getOrElse { log("settings backup: $name NOT written (${it.javaClass.simpleName})"); return }
                log("$line: $counts")
            } else log("settings backup: $name not written (the game's own is complete: $counts)")
            runCatching { writeDoc(gameBaseFile(keptDir, variable), Doc(variable, 0, SvLua.ser(m), now)); mark.delete() }
        }
        val keptNew = SvLua.withoutSkipped(variable, m)
        if (!SvMerge.same(keptNew, keptRaw)) {
            if (setKept(keptDir, variable, keptNew, keptAt, now)) log("settings backup: kept $variable with the game's own entries ($counts)")
            else log("settings backup: $variable kept copy not changed (no backup)")
        }
    }

    /**
     * BEFORE THE GAME STARTS (the client not running): for each of our tables, OUR file under the game's [wtf] folded with the one kept in
     * [keptDir] ([foldOne]: per entry, the game's own values kept unless the kept one is a genuine newer change); the replaced file goes
     * to [backupDir]. Every outcome is one [log] line.
     */
    fun beforeStart(wtf: File, keptDir: File, backupDir: File, now: Long = System.currentTimeMillis(), log: (String) -> Unit) {
        val sv = savedVariablesDir(wtf)
        if (sv == null) { log("settings backup: no WTF/Account/*/SavedVariables yet: nothing written"); return }
        // settings follow (2026-11-18): the account folder's HASH (never its name) for the PC's sync to find the same account (SvSync)
        sv.parentFile?.name?.let { a -> runCatching { keptDir.mkdirs(); SvSync.accountFile(keptDir).writeText(SvSync.accountHash(a)) } }
        runCatching { gameWtfFile(keptDir).writeText(wtf.absolutePath) }
        for ((variable, name) in FILES) foldOne(File(sv, name), keptDir, variable, backupDir, now, log)
    }

    /**
     * THE STREAM'S SYNC (and a PC table with no kept copy): the Thor's own game file folded into the kept copy first ([foldOne] without
     * the game write), so the PC is sent the Thor's FULL settings, never a thin kept copy. Only [variable] when given. Nothing when the
     * game's folder was never seen.
     */
    fun foldGameIn(keptDir: File, now: Long, log: (String) -> Unit, variable: String? = null) {
        val wtf = runCatching { gameWtfFile(keptDir).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { File(it) } ?: return
        val sv = savedVariablesDir(wtf) ?: return
        for ((v, name) in FILES) if (variable == null || v == variable)
            runCatching { foldOne(File(sv, name), keptDir, v, null, now, log) }.onFailure { log("settings sync: $v: the Thor's game file not folded in (${it.javaClass.simpleName})") }
    }

    /** the marker in the new client's WTF folder: the beta's settings were carried across (once) */
    const val CARRIED = ".dsf-carried-from-beta"

    /**
     * THE LAUNCH SWITCH (2026-10-21): the beta's OWN add-on settings into the launch client's WTF, ONCE. For every account folder under
     * [fromWtf] (the beta's) our add-ons' SavedVariables (WowCompanion*.lua) are copied into the same account folder under [toWtf]
     * (_forever_'s; the game names it by the Battle.net account, the same on both clients), each only when the target is missing or
     * older (never over a newer one), with the source's time. The account folder it makes is also what [beforeStart] needs to write
     * the kept copies. The game's own files (bindings, macros, layout) and other add-ons' files are not touched. One [log] line each.
     */
    fun carryOver(fromWtf: File, toWtf: File, log: (String) -> Unit) {
        val marker = File(toWtf, CARRIED)
        if (marker.exists()) return
        val accounts = File(fromWtf, "Account").listFiles()?.filter { it.isDirectory && File(it, "SavedVariables").isDirectory }.orEmpty()
        var n = 0
        for (account in accounts) {
            File(toWtf, "Account/${account.name}/SavedVariables").mkdirs()
            val ours = File(account, "SavedVariables").listFiles()
                ?.filter { it.isFile && it.name.startsWith("WowCompanion") && it.name.endsWith(".lua") }.orEmpty()
            for (src in ours) {
                val target = File(toWtf, "Account/${account.name}/SavedVariables/${src.name}")
                if (target.isFile && target.lastModified() >= src.lastModified()) {
                    log("settings carry-over: ${src.name} not copied (the new client's own is newer)"); continue
                }
                runCatching {
                    WowSwitch.replace(target, src.readBytes())
                    target.setLastModified(src.lastModified())
                    n++
                }.onFailure { log("settings carry-over: ${src.name} NOT copied (${it.javaClass.simpleName})") }
            }
        }
        toWtf.mkdirs()
        runCatching { WowSwitch.replace(marker, "carried $n file(s) from ${fromWtf.path}\n".toByteArray()) }
        log("settings carry-over: $n of the beta's DS Forever settings file(s) copied to ${toWtf.parentFile?.name}")
    }

    /** the kept copy of [d] in [keptDir] (the previous one as .bak), unless [keep] says no; one line for the log */
    fun store(keptDir: File, d: Doc, armed: Boolean): String {
        // SvReset: a reset value the live game still holds after the grace is the player's own again (or the reset never reached it)
        if (SvReset.file(keptDir).isFile) runCatching { SvReset.retire(keptDir, d.variable, SvLua.parse(d.lua), d.savedAt) }
        val f = keptFile(keptDir, d.variable)
        val old = fromJson(runCatching { f.takeIf { it.isFile }?.readText() }.getOrNull())
        if (!keep(old, d, armed)) return "settings backup: kept ${d.variable} (${old?.n} settings; the store is empty at login)"
        if (old != null && old.lua == d.lua) return ""
        keptDir.mkdirs()
        if (f.isFile) runCatching { f.copyTo(File(keptDir, f.name + ".bak"), overwrite = true) }
        val tmp = File(f.path + ".part")
        tmp.writeText(toJson(d))
        if (f.exists() && !f.delete()) { tmp.delete(); return "settings backup: ${d.variable} NOT kept (replace)" }
        return if (tmp.renameTo(f)) "settings backup: kept ${d.variable} (${d.n} settings)" else "settings backup: ${d.variable} NOT kept (rename)"
    }

    /** writes [d] to [target] (the old file to [backupDir] first); one line for the log */
    fun write(target: File, d: Doc, backupDir: File): String {
        if (target.isFile) runCatching { target.copyTo(File(backupDir, target.name), overwrite = true) }
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".part")
        tmp.writeText(fileText(d))
        if (target.exists() && !target.delete()) { tmp.delete(); error("could not replace ${target.name}") }
        check(tmp.renameTo(target)) { "could not write ${target.name}" }
        target.setLastModified(d.savedAt)            // OUR write carries the start's time: a later clean exit of the game is newer
        return "settings backup: wrote ${target.name} (${d.n} settings)"
    }
}
