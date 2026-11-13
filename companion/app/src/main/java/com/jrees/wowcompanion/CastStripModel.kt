package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * THE TAP-TO-CAST BAR (GitHub #43; the add-on half is WowCompanion/ActionBar.lua cb1 and WowCompanionSpells/Book.lua b53): twelve slots
 * of the character's own spells, on Home above the bottom bar in 2 rows of 6 ([CastStripView]); a tap is ONE pad tap on the add-on's
 * secure row "castbar" (cell = the slot's column), the game's own cast on DS Forever's own button. Filled from the Spellbook's
 * "Cast bar" action: the row's pick cell, then the spell's two taps ([toggleCells]); the same action takes a spell off again.
 * Spells only. An edit made in a fight is queued by the add-on until the fight ends; its slot comes dimmed ([Slot.queued]) and with no
 * cell, so it cannot be tapped meanwhile. The cooldowns come from the add-on's `castcd` (facecd's shape: only what the game leaves
 * plain, the length estimated in a fight), never read off the screen.
 * The logic, without the look (unit-tested in dsforever: CastStripModelTest).
 */
object CastStripModel {
    const val ROW = "castbar"
    const val SLOTS = 12
    const val PER_ROW = 6

    /** one slot: [index] 0..11, the spell [id], its [icon] file id, the pad [cell] while the add-on's cell casts it (null = do not tap),
     *  [queued] = a fight's edit waits for the fight's end (drawn dimmed) */
    data class Slot(val index: Int, val id: Int?, val icon: Int?, val cell: Int?, val queued: Boolean)

    /** the `castbar` document: [slots] always 12 (empty ones have no id), [pick] the Spellbook's pick cell (null = no pad row),
     *  [fight] the add-on's lockdown now */
    data class Bar(val slots: List<Slot>, val pick: Int?, val fight: Boolean, val version: String) {
        val hasSpells: Boolean get() = slots.any { it.id != null }
        fun has(spellId: Int?): Boolean = spellId != null && slots.any { it.id == spellId }
        val full: Boolean get() = slots.all { it.id != null }
        /** a Spellbook entry is on the bar: the add-on keeps the id Book's two taps hand it (the book item's action id, Spells `a`),
         *  which is not the entry's [SpellsModel.Entry.spellId] for an overridden spell, so either one counts */
        fun hasEntry(actionId: Int?, spellId: Int?): Boolean = has(actionId?.takeIf { it > 0 }) || has(spellId)
    }

    /** the id the add-on stores for a Spellbook entry: Book's taps carry the book item's action id (Spells `a`), else its spell id */
    fun entryId(actionId: Int?, spellId: Int?): Int? = actionId?.takeIf { it > 0 } ?: spellId

    fun parse(msg: JSONObject): Bar? {
        if (msg.optString("type") != "castbar") return null
        val bySlot = HashMap<Int, Slot>()
        msg.optJSONArray("e")?.let { a ->
            for (k in 0 until a.length()) {
                val o = a.optJSONObject(k) ?: continue
                val s = o.optInt("s", 0)
                if (s !in 1..SLOTS) continue
                val id = o.optInt("id", 0).takeIf { it > 0 }
                val cell = if (o.has("c")) o.optInt("c", -1).takeIf { it in 0 until SLOTS } else null
                bySlot[s - 1] = Slot(s - 1, id, o.optInt("i", 0).takeIf { it > 0 }, cell, o.optInt("q", 0) == 1)
            }
        }
        val slots = (0 until SLOTS).map { bySlot[it] ?: Slot(it, null, null, null, false) }
        val pick = if (msg.has("pick")) msg.optInt("pick", -1).takeIf { it >= 0 } else null
        return Bar(slots, pick, msg.optBoolean("fight", false), msg.optString("v", ""))
    }

    /** the `castcd` message: slot index (0..11) -> its running cooldown; EMPTY = none runs. Null when it is not one. */
    fun parseCd(msg: JSONObject): Map<Int, PadMapModel.FaceCd>? {
        if (msg.optString("type") != "castcd") return null
        val k = msg.optJSONObject("k") ?: return emptyMap()
        val out = HashMap<Int, PadMapModel.FaceCd>()
        for (s in 1..SLOTS) {
            val o = k.optJSONObject(s.toString()) ?: continue
            val d = o.optDouble("d", 0.0); val l = o.optDouble("l", 0.0)
            if (d > 0 && l > 0 && l <= d + 0.001) out[s - 1] = PadMapModel.FaceCd(d, l, o.optBoolean("g", false), o.optBoolean("e", false))
        }
        return out
    }

    /** a finger on slot [i]: the ONE tap (row, cell), or null (empty, queued, not built) */
    fun tapCell(bar: Bar?, i: Int): Pair<String, Int>? {
        val s = bar?.slots?.getOrNull(i) ?: return null
        if (s.id == null || s.queued) return null
        return s.cell?.let { ROW to it }
    }

    /** the Spellbook's "Cast bar": the pick, then the spell's two taps (Book's); null = it cannot be sent (no row, a full bar for a
     *  spell not on it, no taps for the spell) */
    fun toggleCells(bar: Bar?, spellId: Int?, spellCells: List<Pair<String, Int>>?): List<Pair<String, Int>>? {
        val b = bar ?: return null
        val pick = b.pick ?: return null
        if (spellCells.isNullOrEmpty()) return null
        if (!b.has(spellId) && b.full) return null
        return listOf(ROW to pick) + spellCells
    }

    /** the Settings choice: [setting] null = never chosen -> shown only while the bar has spells (the default) */
    fun shown(setting: Boolean?, bar: Bar?): Boolean = setting ?: (bar?.hasSpells == true)

    /** slot [i]'s place in the 2 x 6 grid: (row, column) */
    fun gridOf(i: Int): Pair<Int, Int> = (i / PER_ROW) to (i % PER_ROW)

    /** the cooldown sweep's part still to run at [nowMs] (elapsedRealtime) for a cooldown that arrived at [arrivalMs]: 1 = all, 0 = done */
    fun sweepLeft(cd: PadMapModel.FaceCd, arrivalMs: Long, nowMs: Long): Float {
        val start = PadMapModel.cdStartMs(cd, arrivalMs); val dur = PadMapModel.cdDurMs(cd)
        if (dur <= 0) return 0f
        return (1f - (nowMs - start).toFloat() / dur.toFloat()).coerceIn(0f, 1f)
    }

    /** the live bar (the host sets it from every `castbar` document); listeners hear each change */
    object Live {
        @Volatile var bar: Bar? = null
            private set
        private val listeners = ArrayList<() -> Unit>()
        fun onChange(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
        fun set(b: Bar) {
            if (b == bar) return
            bar = b
            val ls = synchronized(listeners) { listeners.toList() }
            for (l in ls) l()
        }
    }
}
