package com.jrees.wowcompanion

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * [CombatKeep] on the device: the backup files (Documents/DS Forever/combat/<name-realm>.json, shared storage: an uninstall keeps them),
 * the once-per-run check at the first padmap, and the restore's taps (one batch per setting, spaced). Every step is one WCSETUP line.
 */
class CombatKeepHost(
    private val ui: Handler,
    private val tap: (List<Pair<String, Int>>) -> Boolean,
    /** a spell's Bind taps on the `spells` row (Book b38: its group, then its offset), from the book on show; null = none */
    private val column: (Int) -> List<Pair<String, Int>>?,
    /** the book came (the columns can be looked up) */
    private val bookReady: () -> Boolean,
    private val fighting: () -> Boolean,
) {
    private val checked = HashSet<String>()
    /** the keys whose backup is another class's this run (Safety k7): nothing of theirs is touched */
    private val otherClass = HashSet<String>()
    private var run = Long.MIN_VALUE
    private var restoring: CombatKeep.Backup? = null
    private var restoreUntil = 0L
    private var retried = false
    /** test22 P0: writes may REMOVE entries only once the store is confirmed (see CombatKeep.safeMerge) */
    private var armed = false
    private val written = HashMap<String, String>()
    /** the keys whose adoption ([adopted]) was logged this run (one line each, not one per padmap) */
    private val adoptSaid = HashSet<String>()

    private fun log(s: String) = Log.i(TAG, "combat keep: $s")

    private fun dir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "DS Forever/combat")

    private fun read(who: String): CombatKeep.Backup? = runCatching {
        val f = File(dir(), CombatKeep.fileName(who))
        if (f.isFile) CombatKeep.fromJson(f.readText()) else null
    }.getOrNull()

    private fun write(b: CombatKeep.Backup) {
        val json = CombatKeep.toJson(b)
        if (written[b.who] == json) return
        runCatching {
            val d = dir().also { it.mkdirs() }
            val f = File(d, CombatKeep.fileName(b.who))
            val tmp = File(d, f.name + ".part")
            if (f.isFile) runCatching { f.copyTo(File(d, f.name + ".bak"), overwrite = true) }     // the previous backup kept
            tmp.writeText(json)
            if (!tmp.renameTo(f)) { f.delete(); check(tmp.renameTo(f)) { "rename" } }
            written[b.who] = json
            log("wrote ${b.entries.size} entries")                                    // the k6 review (item 10): never a name
        }.onFailure { log("NOT saved (${it.javaClass.simpleName})") }          // the k6 review round 3 (item 4): its message names the file
    }

    /** Reset skills (ResetRun) took [who]'s backup away (null = every character's): nothing of it is put back or remembered this run */
    fun resetDone(who: String?) {
        if (who == null) written.clear() else written.remove(who)
        restoring = restoring?.takeIf { who != null && it.who != who }
        if (who != null) checked += who
    }

    /** every padmap; only CURRENT ones count */
    fun onMap(map: PadMapModel.Map, current: Boolean) {
        if (!current) return
        val who = CombatKeep.who(map) ?: return
        val r = LauncherHooks.gameStartSec
        if (r != run) { run = r; checked.clear(); otherClass.clear(); adoptSaid.clear(); restoring = null; retried = false; armed = false }
        restoring?.let { b ->
            if (SystemClock.uptimeMillis() < restoreUntil) return              // nothing written while the restore's taps run
            val miss = CombatKeep.missing(b, map)
            restoring = null
            if (miss.isEmpty()) { log("restored ${b.entries.size}"); armed = true }
            else if (!retried && !fighting()) {
                retried = true
                log("restore left ${miss.size} not on (${miss.joinToString { "${it.set}/${it.pos} ${it.name}" }}): once more")
                start(b.copy(entries = miss), map)
                return
            } else log("restore left ${miss.size} not on: the backup KEPT as it is (only additions are written this run)")
        }
        if (who in otherClass) return
        if (who !in checked) {
            val backup0 = read(who)
            // Safety k7 (the k6 review, item 1): a backup written for another class is another character's (a same-name character on
            // another realm): never restored, merged, written over or folded into; left as it is for this run
            if (CombatKeep.otherClass(map, backup0)) { otherClass += who; log("the backup is another class's: left as it is (nothing restored or written)"); return }
            foldOrphans(who, map.dpad?.cls)
            // Safety k11 (the k10 review, round 1): no backup of this key at all (a fresh key after the saved tables were lost): the
            // one backup of this NAME under another realm is this character's (CombatKeep.adopt), restored into the add-on's key
            val own = read(who)
            // k12 (the review, round 2): only a fresh key nobody chose (the add-on's who.ad): never over the key question's answer
            val adopt = if (own == null && CombatKeep.mayAdopt(map) && !File(dir(), CombatKeep.fileName(who)).exists()) adopted(who, map.dpad?.cls) else null
            val backup = own ?: adopt
            if (CombatKeep.needsRestore(map, backup)) {
                if (fighting() || !bookReady()) return                          // the next padmap tries again
                checked += who
                if (adopt != null) write(adopt)                                 // this key's own backup from now on (only additions until armed)
                log("kept (store empty at login): ${backup!!.entries.size} entries")
                start(backup, map)
                return
            }
            checked += who
            armed = true                                                    // nothing owed: the store is the truth
        }
        CombatKeep.safeMerge(written[who]?.let { CombatKeep.fromJson(it) } ?: read(who), map, armed)?.let { write(it) }
    }

    /**
     * RealmFix (the 11-20 hunter, test build 1513): the backups a realm-less session wrote for [who]'s name ("Name-_.json" from "Name-?",
     * "Name-.json") fold into the real one once, at the run's first check: the real backup's entries always stay; an orphan entry the
     * real one lacks comes in only from a NEWER orphan file ([RealmFix.combat]); both files are copied first (combat/realm-fix, 2 per
     * file) and the orphan files then go. Left alone while the name has backups under two realms (never a guess). No names logged.
     * The k6 review round 2 (item B): and left alone unless the device's census holds the name under [who]'s realm ALONE (RealmFix.mayFold:
     * our saved tables, the app's per-character files, the other backups, the keys seen live) - a same-name character on another realm
     * with no file of its own never takes the hunter's orphan -, and an orphan with entries is taken only when it says [cls], the class
     * playing now (RealmFix.combatTakes: one of no class is left as it is, never restored onto this character's bars).
     */
    private fun foldOrphans(who: String, cls: String?) {
        val name = CharKey.nameOf(who) ?: return
        val d = dir()
        val orphans = RealmFix.combatOrphanFiles(name).map { File(d, it) }.filter { it.isFile }
        if (orphans.isEmpty()) return
        val realFile = File(d, CombatKeep.fileName(who))
        val reals = d.listFiles()?.filter { f ->
            f.isFile && f.name.startsWith("$name-") && f.name.endsWith(".json") && orphans.none { it.name == f.name }
        }.orEmpty()
        if (reals.any { it.name != realFile.name }) { log("orphan backup left as it is (this name has a backup under another realm)"); return }
        if (!RealmFix.mayFold(who)) { log("orphan backup left as it is (this name is not known under this realm alone)"); return }
        val bak = File(d, RealmFix.DIR)
        val now = System.currentTimeMillis()
        for (f in orphans) runCatching {
            val orphan = CombatKeep.fromJson(f.readText())
            if (!RealmFix.combatTakes(orphan, cls)) { log("orphan backup left as it is (not this class's)"); return@runCatching }
            val real = read(who)
            val next = RealmFix.combat(real, if (realFile.isFile) realFile.lastModified() else Long.MIN_VALUE, orphan, f.lastModified(), who, cls)
            check(RealmFix.backup(f, bak, now)) { "no backup" }
            File(d, f.name + ".bak").takeIf { it.isFile }?.let { check(RealmFix.backup(it, bak, now)) { "no backup" } }
            if (next != null) {
                check(RealmFix.backup(realFile, bak, now)) { "no backup" }
                write(next)
                check(read(who)?.entries?.size == next.entries.size) { "the real backup did not take" }
            }
            File(d, f.name + ".bak").delete()
            check(f.delete() || !f.exists()) { "not removed" }
            log("orphan backup folded: ${next?.let { "${it.entries.size - (real?.entries?.size ?: 0)} entries into the real one" } ?: "nothing taken"}, removed")
        }.onFailure { log("orphan backup NOT folded (${it.javaClass.simpleName})") }
    }

    /** [who]'s name's backups under other realms (the combat folder's "Name-*.json"; never the orphans' or the copies), as [CombatKeep.adopt]
     *  takes them; logged with counts only */
    private fun adopted(who: String, cls: String?): CombatKeep.Backup? = runCatching {
        val name = CharKey.nameOf(who) ?: return@runCatching null
        val own = CombatKeep.fileName(who)
        val orphans = RealmFix.combatOrphanFiles(name).toSet()
        val others = dir().listFiles()?.filter { f ->
            f.isFile && f.name != own && f.name.startsWith("$name-") && f.name.endsWith(".json") && f.name !in orphans
        }.orEmpty().mapNotNull { f -> runCatching { CombatKeep.fromJson(f.readText()) }.getOrNull() }
        val say = adoptSaid.add(who)
        CombatKeep.adopt(who, cls, others)?.also {
            if (say) log("no backup of this key: this name's one backup under another saved place taken (${it.entries.size} entries; that file left as it is)")
        } ?: run { if (say && others.size > 1) log("no backup of this key: ${others.size} of this name under other saved places (never a guess)"); null }
    }.getOrNull()

    /** a restore's taps are running now (their answers are not the player's) */
    fun restoringNow(): Boolean = restoring != null

    /** a setting the PLAYER changed (an ok padack: combat / cface / uncombat / uncface / clear): from now on the store is the truth */
    fun onPlayerChange() { if (restoring == null) armed = true }

    private fun start(backup: CombatKeep.Backup, map: PadMapModel.Map) {
        val plan = CombatKeep.plan(backup, map.page, column)
        if (plan.skipped.isNotEmpty()) log("no touch cell for ${plan.skipped.joinToString { it.name }}: not restored")
        if (plan.batches.isEmpty()) { log("nothing to restore by touch"); return }
        log("restoring ${plan.restored.size}")
        restoring = backup
        restoreUntil = SystemClock.uptimeMillis() + plan.batches.size * GAP_MS + SETTLE_MS
        plan.batches.forEachIndexed { i, cells ->
            ui.postDelayed({ if (!fighting() && !tap(cells)) log("a restore tap was not sent") }, i * GAP_MS)
        }
    }

    companion object {
        private const val TAG = "WCSETUP"
        private const val GAP_MS = 1_500L
        private const val SETTLE_MS = 3_000L
    }
}
