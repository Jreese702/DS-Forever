package com.jrees.wowcompanion

import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RESET TO DEFAULTS, THE APP'S SIDE (the user, 2026-11-19; what each button touches is [ResetPlan]'s). One [run]:
 *  1. the app's preferences of the button's kinds go (their defaults come back): a backup of the removed values first, the newest
 *     [SvReset.KEEP_BACKUPS] per file in Documents/DS Forever/settings/reset-backups; the DS WoW toolbar's choice is set ON (its default,
 *     pending until the game says it: DpadChoice), never removed (that would ask the once-per-character prompt again);
 *  2. the add-on's saved tables, per entry, in the kept copy, with a reset mark each (SvReset: the next Local start's fold and the
 *     stream's sync leave the old values out, so neither brings them back);
 *  3. Skills: the character's In-combat backup (CombatKeep's file: it is put back when the store is empty at login) moved to the backups;
 *  4. Settings: the game settings DS Forever recorded the player's own values for (GameConfigWtf.restoreOriginals) given back now, or -
 *     the game running - at its next start (GameConfigWtf.RESET_RESTORE). Nothing else of the game's.
 * [who] = the character playing now (or last played); [all] = every character (Everything's "All characters"). Pure but for the files
 * and the preferences it is handed: ResetRunTest runs it.
 */
class ResetRun(
    private val prefsOf: (String) -> SharedPreferences,
    private val keptDir: File,
    private val combatDir: File,
    private val log: (String) -> Unit,
) {
    data class Result(val prefs: Int, val entries: Int, val combat: Int, val gameKeys: List<String>, val gameLater: Boolean,
                      val refused: List<String>) {
        val changed get() = prefs + entries + combat + gameKeys.size > 0 || gameLater
    }

    private val backups get() = File(keptDir, SvReset.BACKUP_DIR)

    /** [text] kept as a dated backup named [name] (the newest [SvReset.KEEP_BACKUPS] of it stay); true = kept */
    private fun backupText(name: String, text: String, now: Long): Boolean = runCatching {
        val d = backups.also { it.mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(now))
        File(d, "$name.$stamp.bak").writeText(text)
        d.listFiles()?.filter { it.name.startsWith("$name.") && it.name.endsWith(".bak") }?.sortedByDescending { it.name }
            ?.drop(SvReset.KEEP_BACKUPS)?.forEach { it.delete() }
        true
    }.getOrDefault(false)

    private fun jsonOf(m: Map<String, *>): String = JSONObject().apply { for ((k, v) in m) put(k, v) }.toString()

    fun run(button: ResetPlan.Button, who: String?, all: Boolean, gameRunning: Boolean, now: Long = System.currentTimeMillis()): Result {
        val kinds = button.kinds
        val refused = ArrayList<String>()
        var prefs = 0
        // 1. the preferences
        // fix round 3: one character's Layouts reset keeps the device's default record (the seed of every first login): Home's Classic is
        // WRITTEN under the character's own key, so it comes back as Classic, never as that default
        val classicFor = if (ResetPlan.Kind.LAYOUTS in kinds && !all) who?.takeIf { ResetPlan.isCharKey(it) } else null
        for (file in ResetPlan.FILES) {
            val p = prefsOf(file)
            val have = p.all
            val rm = ResetPlan.prefKeys(file, have.keys, kinds, who, all)
            val classic = if (file == "home" && classicFor != null) HomeLayoutStore.KEY_PREFIX + classicFor else null
            if (rm.isEmpty() && classic == null) continue
            if (rm.isNotEmpty() && !backupText("prefs-$file", jsonOf(have.filterKeys { it in rm }), now)) { refused += file; log("settings reset: $file not reset (no backup)"); continue }
            val e = p.edit()
            for (k in rm) {
                // the toolbar's choice: ON (its default), pending until the game says it; the prompt stays answered. The k6 review round 2
                // (item G): a realm-less session's orphan "choice_Name-" is REMOVED (never given the default: no orphan key is written)
                if (file == "dpad" && !orphanKey(k)) e.putBoolean(k, true).putBoolean("pend_$k", true).remove("owed_$k") else e.remove(k)
            }
            if (classic != null) e.putString(classic, HomeLayoutJson.json(HomeLayout()))
            e.commit()
            prefs += rm.size
        }
        if (ResetPlan.Kind.SKILLS in kinds) {
            // the spell radials' names, icons, tap / hold (one JSON of every character's)
            val rp = prefsOf("radial_meta")
            val allMeta = runCatching { JSONObject(rp.getString("all", "{}") ?: "{}") }.getOrDefault(JSONObject())
            val gone = allMeta.keys().asSequence().toList().filter {
                if (all) ResetPlan.isCharKey(it) || CharKey.isOrphan(it)
                else it == who || (who != null && CharKey.isOrphan(it) && CharKey.nameOf(it) == CharKey.nameOf(who) &&     // its orphans too
                    RealmFix.resetTakesOrphans(who) &&                                      // the k6 review round 3 (item 5)
                    allMeta.keys().asSequence().none { o -> o != who && ResetPlan.isCharKey(o) && CharKey.nameOf(o) == CharKey.nameOf(who) })
            }
            if (gone.isNotEmpty() && backupText("prefs-radial_meta", allMeta.toString(), now)) {
                for (k in gone) allMeta.remove(k)
                rp.edit().putString("all", allMeta.toString()).commit(); prefs += gone.size
            }
            // the radials' kept documents, when they are this character's (the game sends its own again)
            val wr = prefsOf("wc_radial")
            if (all || (who != null && wr.getString("who", null) == who)) {
                if (wr.contains("doc") || wr.contains("sdoc")) { wr.edit().remove("doc").remove("sdoc").commit(); prefs++ }
            }
        }
        // 2. the add-on's saved tables (the kept copy, per entry, with the reset's marks)
        val tables = SvKeep.FILES.keys.associateWith { v -> listOfNotNull(keptTable(v), gameTable(v)) }
        val targets = ResetPlan.svTargets(kinds, who, all, tables)
        val done = if (targets.isEmpty()) SvReset.Done(0, 0, emptyList()) else SvReset.apply(keptDir, targets, now, log)
        refused += done.refused
        // 3. the In-combat backup (put back when the store is empty at login: it would undo the reset)
        var combat = 0
        if (ResetPlan.Kind.SKILLS in kinds) {
            val files = if (all) combatDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }.orEmpty()
                else listOfNotNull(who?.let { File(combatDir, CombatKeep.fileName(it)) }?.takeIf { it.isFile }) + combatOrphans(who)
            for (f in files) {
                if (!SvReset.backup(f, backups, now)) { refused += f.name; continue }
                if (f.delete()) combat++
            }
        }
        // 4. the game settings DS Forever recorded the player's own values for
        var gameKeys = emptyList<String>()
        var later = false
        if (ResetPlan.Kind.SETTINGS in kinds) {
            val wtf = runCatching { SvKeep.gameWtfFile(keptDir).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { File(it) }
            if (wtf != null && GameConfigWtf.hasOriginals(wtf)) {
                if (gameRunning) later = runCatching { File(wtf, GameConfigWtf.RESET_RESTORE).writeText("reset settings\n"); true }.getOrDefault(false)
                else gameKeys = runCatching { GameConfigWtf.restoreOriginals(wtf) }.getOrDefault(emptyList())
            }
        }
        val r = Result(prefs, done.reset, combat, gameKeys, later, refused)
        log("settings reset: ${button.name.lowercase()}${if (all) " (all characters)" else ""}: ${r.prefs} saved values, ${r.entries} " +
            "add-on entries, ${r.combat} In-combat backups, ${r.gameKeys.size} game settings given back${if (later) " (at the next start)" else ""}" +
            if (refused.isEmpty()) "" else "; ${refused.size} not done (no backup)")
        return r
    }

    /**
     * the k6 review (item 3): the In-combat backups a realm-less session wrote for [who]'s name ("Name-.json", "Name-_.json"); a reset
     * takes them with the real one (else the next run's fold would make them the real backup and put the fight layer back). Left while
     * the name has a backup under another realm (never a guess).
     */
    /** [k] ends with a realm-less session's orphan character key ("<prefix>Name-", "Name-?", "Name-_", a marker) */
    private fun orphanKey(k: String): Boolean {
        val i = k.lastIndexOf('-').takeIf { it > 0 } ?: return false
        var s = i
        while (s > 0 && k[s - 1].isLetter()) s--
        return CharKey.isOrphan(k.substring(s))
    }

    private fun combatOrphans(who: String?): List<File> {
        val w = who?.takeIf { ResetPlan.isCharKey(it) } ?: return emptyList()
        val name = CharKey.nameOf(w) ?: return emptyList()
        if (!RealmFix.resetTakesOrphans(w)) return emptyList()            // the k6 review round 3 (item 5): the device census decides too
        val orphanNames = RealmFix.combatOrphanFiles(name)
        val own = CombatKeep.fileName(w)
        val other = combatDir.listFiles()?.any { f ->
            f.isFile && f.name.startsWith("$name-") && f.name.endsWith(".json") && f.name != own && f.name !in orphanNames
        } ?: false
        if (other) return emptyList()
        return orphanNames.map { File(combatDir, it) }.filter { it.isFile }
    }

    private fun keptTable(v: String): Map<Any, Any>? =
        runCatching { SvKeep.fromJson(SvKeep.keptFile(keptDir, v).readText())?.let { SvLua.parse(it.lua) } }.getOrNull()

    private fun gameTable(v: String): Map<Any, Any>? = runCatching {
        val wtf = SvKeep.gameWtfFile(keptDir).readText().trim()
        val sv = SvKeep.savedVariablesDir(File(wtf)) ?: return null
        SvLua.parse(File(sv, SvKeep.FILES.getValue(v)).readText(), v)
    }.getOrNull()
}
