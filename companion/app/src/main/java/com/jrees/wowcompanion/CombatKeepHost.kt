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
    private var run = Long.MIN_VALUE
    private var restoring: CombatKeep.Backup? = null
    private var restoreUntil = 0L
    private var retried = false
    /** test22 P0: writes may REMOVE entries only once the store is confirmed (see CombatKeep.safeMerge) */
    private var armed = false
    private val written = HashMap<String, String>()

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
            log("wrote ${b.entries.size} entries for ${b.who}")
        }.onFailure { log("NOT saved for ${b.who} (${it.message})") }
    }

    /** every padmap; only CURRENT ones count */
    fun onMap(map: PadMapModel.Map, current: Boolean) {
        if (!current) return
        val who = CombatKeep.who(map) ?: return
        val r = LauncherHooks.gameStartSec
        if (r != run) { run = r; checked.clear(); restoring = null; retried = false; armed = false }
        restoring?.let { b ->
            if (SystemClock.uptimeMillis() < restoreUntil) return              // nothing written while the restore's taps run
            val miss = CombatKeep.missing(b, map)
            restoring = null
            if (miss.isEmpty()) { log("restored ${b.entries.size} for ${b.who}"); armed = true }
            else if (!retried && !fighting()) {
                retried = true
                log("restore left ${miss.size} not on (${miss.joinToString { "${it.set}/${it.pos} ${it.name}" }}): once more")
                start(b.copy(entries = miss), map)
                return
            } else log("restore left ${miss.size} not on: the backup KEPT as it is (only additions are written this run)")
        }
        if (who !in checked) {
            val backup = read(who)
            if (CombatKeep.needsRestore(map, backup)) {
                if (fighting() || !bookReady()) return                          // the next padmap tries again
                checked += who
                log("kept (store empty at login): ${backup!!.entries.size} entries of $who")
                start(backup, map)
                return
            }
            checked += who
            armed = true                                                    // nothing owed: the store is the truth
        }
        CombatKeep.safeMerge(written[who]?.let { CombatKeep.fromJson(it) } ?: read(who), map, armed)?.let { write(it) }
    }

    /** a restore's taps are running now (their answers are not the player's) */
    fun restoringNow(): Boolean = restoring != null

    /** a setting the PLAYER changed (an ok padack: combat / cface / uncombat / uncface / clear): from now on the store is the truth */
    fun onPlayerChange() { if (restoring == null) armed = true }

    private fun start(backup: CombatKeep.Backup, map: PadMapModel.Map) {
        val plan = CombatKeep.plan(backup, map.page, column)
        if (plan.skipped.isNotEmpty()) log("no touch cell for ${plan.skipped.joinToString { it.name }}: not restored")
        if (plan.batches.isEmpty()) { log("nothing to restore by touch for ${backup.who}"); return }
        log("restoring ${plan.restored.size} of ${backup.who}")
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
