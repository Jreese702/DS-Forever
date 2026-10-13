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
 * <AddOn>.lua from it - only when the game has nothing newer (its own file is older than the kept one) and never an empty table. The
 * game then loads everything itself at login. Nothing of the game's own (bars, bindings, other add-ons' files) is ever touched.
 * Pure (java.io + org.json): tools/setup_test runs it.
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

    /** what to do with OUR file [target] before the game starts: null = write it, else why not */
    fun skipWhy(target: File, d: Doc?): String? = when {
        d == null -> "nothing kept"
        d.n == 0 -> "the kept one is empty"
        target.isFile && target.lastModified() > d.savedAt -> "the game's own is newer"
        target.isFile && target.readText() == fileText(d) -> "already the same"
        else -> null
    }

    /** the kept file of table [variable] in [keptDir] */
    fun keptFile(keptDir: File, variable: String) = File(keptDir, "$variable.json")

    /**
     * BEFORE THE GAME STARTS (the client not running): for each of our tables, OUR file under the game's [wtf] from the one kept in
     * [keptDir], by [skipWhy]'s rules; the replaced file goes to [backupDir]. Every outcome is one [log] line.
     */
    fun beforeStart(wtf: File, keptDir: File, backupDir: File, log: (String) -> Unit) {
        val sv = savedVariablesDir(wtf)
        if (sv == null) { log("settings backup: no WTF/Account/*/SavedVariables yet: nothing written"); return }
        for ((variable, name) in FILES) {
            val d = fromJson(runCatching { keptFile(keptDir, variable).takeIf { it.isFile }?.readText() }.getOrNull())
            val target = File(sv, name)
            val why = skipWhy(target, d)
            if (why != null) { log("settings backup: $name not written ($why)"); continue }
            log(runCatching { write(target, d!!, backupDir) }.getOrElse { "settings backup: $name NOT written (${it.message})" })
        }
    }

    /** the kept copy of [d] in [keptDir] (the previous one as .bak), unless [keep] says no; one line for the log */
    fun store(keptDir: File, d: Doc, armed: Boolean): String {
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
        target.setLastModified(d.savedAt)            // OUR write carries the kept copy's time: "newer" stays the game's own clean exit
        return "settings backup: wrote ${target.name} (${d.n} settings)"
    }
}
