package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * THE IN-COMBAT SETTINGS' BACKUP (the user's ruling after test20, "Both (Rec.)"; Tracking proved on the Thor that the game had never
 * written WowCompanionSpells.lua since the fresh install: SavedVariables are written only on a clean exit, and every update kills the
 * game). DS Forever keeps each character's In-combat settings - the base X / Y / A fight spells and every button's overlay fight spell
 * (the D-pad's included) - in a small JSON per character (name-realm) in Documents/DS Forever (shared storage: it survives an uninstall
 * and a crash). No credentials, nothing of the game's.
 *
 * THE RULE (one game run at a time):
 *  - the FIRST padmap of a run for a character decides: the add-on's store is empty, the backup is not, out of a fight -> RESTORE it
 *    (the Controller tab's own touch route: padtab 14 = the quiet enter, then per setting page / set / button picks, the one-shot
 *    "combat layer" pick and the spell's Bind column = ONE put each; talents 28 leaves). Otherwise the add-on's store is the truth.
 *  - from then on every padmap is the truth: the backup follows it (the page on show is replaced, other pages kept; the faces always),
 *    so a setting the player clears is cleared in the backup too. Nothing is written while a restore runs.
 * Pure (org.json only): tools/padmap_test runs it.
 */
object CombatKeep {
    /** one setting: [face] = a base X / Y / A fight spell (not per page: [page] 0), else the overlay fight spell of button [set]/[pos] on [page] */
    data class Entry(val page: Int, val set: Int, val pos: Int, val id: Int, val name: String, val face: Boolean)
    data class Backup(val who: String, val entries: List<Entry>)

    /** the character the add-on's padmap names (its name-realm key, as the add-on's own per-character stores), or null */
    fun who(map: PadMapModel.Map): String? = map.dpad?.name?.takeIf { it.isNotEmpty() }?.let { "$it-${map.dpad.realm ?: "?"}" }

    /** what the add-on holds now: the faces' fight spells and the page on show's overlay fight spells (spells only, never an item) */
    fun current(map: PadMapModel.Map): List<Entry> {
        val out = ArrayList<Entry>()
        for (st in map.sets) for (sl in st.slots) {
            val face = st.set == 1 && PadMapModel.isFace(sl) && sl.fight != null
            val ref = when {
                face -> sl.fight
                sl.kind == PadMapModel.Kind.COMBAT && sl.ov -> sl.combat
                else -> null
            } ?: continue
            val id = ref.id ?: continue
            if (ref.item) continue
            out += Entry(if (face) 0 else map.page, st.set, sl.pos, id, ref.name, face)
        }
        return out.sortedWith(compareBy({ it.page }, { it.set }, { it.pos }))
    }

    /** the backup after this padmap: the page on show and the faces as the add-on says, every other page as it was */
    fun merge(old: Backup?, map: PadMapModel.Map): Backup? {
        val who = who(map) ?: return null
        val kept = old?.takeIf { it.who == who }?.entries.orEmpty().filter { !it.face && it.page != map.page }
        return Backup(who, (kept + current(map)).sortedWith(compareBy({ it.page }, { it.set }, { it.pos })))
    }

    /**
     * test22 device P0: the backup was written EMPTY by the new run's first padmap after a restore that did not take. Until the store is
     * confirmed ([armed]: nothing was owed at login, the restore took, or the player changed a setting), a write only ADDS: the backup
     * never loses an entry to an empty or partial store.
     */
    fun safeMerge(old: Backup?, map: PadMapModel.Map, armed: Boolean): Backup? {
        val now = merge(old, map) ?: return null
        if (armed || old == null || old.who != now.who) return now
        val key = { e: Entry -> Triple(e.page, e.set, e.pos) to e.face }
        val have = now.entries.map(key).toSet()
        return now.copy(entries = (now.entries + old.entries.filter { key(it) !in have }).sortedWith(compareBy({ it.page }, { it.set }, { it.pos })))
    }

    /** the first padmap of a run: the add-on lost its store (a killed game never saved it) and the backup has it -> restore */
    fun needsRestore(map: PadMapModel.Map, backup: Backup?): Boolean =
        backup != null && backup.who == who(map) && backup.entries.isNotEmpty() && current(map).isEmpty() && !map.combat && !map.fight

    /** the taps that put [backup] back, one batch per setting (ONE put each) between the quiet enter and the leave; [column] = a spell's
     *  Bind column on the `spells` row (null = no touch cell: that setting is [Plan.skipped]); [page] = the page on show, picked back last */
    data class Plan(val batches: List<List<Pair<String, Int>>>, val restored: List<Entry>, val skipped: List<Entry>)

    fun plan(backup: Backup, page: Int, column: (Int) -> List<Pair<String, Int>>?): Plan {
        val enter = listOf(PadMapModel.ROW_PADTAB to COL_QUIET_ENTER)
        val batches = arrayListOf(enter)
        val done = ArrayList<Entry>(); val skipped = ArrayList<Entry>()
        for (e in backup.entries) {
            val cells = column(e.id)
            if (cells.isNullOrEmpty()) { skipped += e; continue }
            batches += listOf(
                PadMapModel.ROW to PadMapModel.colPage(if (e.face) page else e.page),
                PadMapModel.ROW to PadMapModel.colSet(e.set),
                PadMapModel.ROW to PadMapModel.colPos(e.pos),
                PadMapModel.ROW_LAYOUT to PadMapModel.COL_COMBAT_LAYER) + cells
            done += e
        }
        batches += listOf(PadMapModel.ROW to PadMapModel.colPage(page), PadMapModel.ROW to PadMapModel.COL_LEFT)
        return Plan(if (done.isEmpty()) emptyList() else batches, done, skipped)
    }

    /** Book b35: the Controller tab opened for touch without the room or the controller */
    const val COL_QUIET_ENTER = 14

    /** the settings of [b] the add-on does NOT show after a restore (on the page on show and the faces): what did not take */
    fun missing(b: Backup, map: PadMapModel.Map): List<Entry> {
        val now = current(map).map { Triple(it.set, it.pos, it.id) to it.face }.toSet()
        return b.entries.filter { (it.face || it.page == map.page) && (Triple(it.set, it.pos, it.id) to it.face) !in now }
    }

    fun toJson(b: Backup): String = JSONObject().put("v", 1).put("who", b.who).put("entries", JSONArray().apply {
        for (e in b.entries) put(JSONObject().put("page", e.page).put("set", e.set).put("pos", e.pos).put("id", e.id).put("n", e.name)
            .apply { if (e.face) put("face", true) })
    }).toString()

    fun fromJson(s: String?): Backup? = if (s == null) null else try {
        val o = JSONObject(s)
        val a = o.optJSONArray("entries") ?: JSONArray()
        val es = (0 until a.length()).mapNotNull { i ->
            val e = a.optJSONObject(i) ?: return@mapNotNull null
            val id = e.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
            Entry(e.optInt("page", 1), e.optInt("set", 1), e.optInt("pos", 1), id, e.optString("n", ""), e.optBoolean("face", false))
        }
        o.optString("who").takeIf { it.isNotEmpty() }?.let { Backup(it, es) }
    } catch (_: Throwable) { null }

    /** the backup's file name for a character: letters, digits, '-', '_' and spaces kept, anything else '_' */
    fun fileName(who: String): String = who.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ') it else '_' }.joinToString("") + ".json"
}
