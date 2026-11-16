package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * THE PET'S AUTOCAST, LIVE (the user, 2026-11-16: "hunters need to be able to visually see when their pets abilities are active like
 * growl, and a way to turn it off from those radials"). Pure: the add-on's {"type":"petauto"} document (Quests q62, sent on the game's
 * PET_BAR_UPDATE / PET_BAR_UPDATE_COOLDOWN / UNIT_PET / the pet's health, only when it changes), by PET BAR SLOT: whether each ability
 * that may cast itself does now, and its toggle cell on the add-on's pfocus row. The pet's wheels (TargetRadialView) and their menu
 * documents (`au` / `ac` / `as` on a row, [seed]) read the state from here, so an open wheel follows the game.
 *
 * A PRESS ([pressed]) shows the flip at once (the game's answer is a PET_BAR_UPDATE a moment later): it stands until a document says the
 * same, or [PENDING_MS] passes (a tap the game never saw: back to what it last said). A document that still says the old state (sent for
 * another reason before the game took the toggle) does not undo it.
 */
object PetAuto {
    /** one ability's state: [on] it casts itself now; [cell] its toggle on pfocus (null = none built for it: a fight changed the pet) */
    data class Slot(val slot: Int, val name: String, val on: Boolean, val cell: Int?)

    @Volatile var slots: Map<Int, Slot> = emptyMap()
        private set
    /** a pet is out / it is dead / the add-on says a fight is on (from the last document) */
    @Volatile var pet = false
        private set
    @Volatile var dead = false
        private set
    @Volatile var fight = false
        private set
    /** when the last document (or a menu's rows, [seed]) came, uptime ms; 0 = never */
    @Volatile var at = 0L
        private set
    private val pending = HashMap<Int, Pair<Boolean, Long>>()

    const val PENDING_MS = 2_500L

    /** the add-on's document ([now] = uptime ms) */
    @Synchronized fun onDoc(d: JSONObject, now: Long) {
        val a = d.optJSONArray("s")
        val m = LinkedHashMap<Int, Slot>()
        if (a != null) for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val s = o.optInt("s", 0).takeIf { it in 1..10 } ?: continue
            m[s] = Slot(s, o.optString("n", ""), o.optInt("on", 0) == 1, if (o.has("ac")) o.optInt("ac") else null)
        }
        slots = m
        pet = d.optInt("pet", 0) == 1
        dead = d.optInt("dead", 0) == 1
        fight = d.optInt("fight", 0) == 1
        at = now
        settle(now)
    }

    /** a menu document's row (Quests q62 `au` / `ac` / `as`): the state as the game said it when the menu was read */
    @Synchronized fun seed(slot: Int, name: String, on: Boolean, cell: Int?, now: Long) {
        if (slot !in 1..10) return
        val m = LinkedHashMap(slots)
        m[slot] = Slot(slot, name, on, cell)
        slots = m
        pet = true; dead = false
        at = now
        settle(now)
    }

    /** a toggle pressed for [slot], whose state was [wasOn] as drawn: the flip shows at once */
    @Synchronized fun pressed(slot: Int, wasOn: Boolean, now: Long) { pending[slot] = !wasOn to now }

    /** [slot] casts itself, as shown now: a press not yet answered, else the game's last word; null = not one of the pet's */
    @Synchronized fun on(slot: Int, now: Long): Boolean? {
        pending[slot]?.let { (want, t) -> if (now - t <= PENDING_MS) return want else pending.remove(slot) }
        return slots[slot]?.on
    }

    /** the toggle cell for [slot] now (null = none built for what the slot holds) */
    fun cell(slot: Int): Int? = slots[slot]?.cell

    /** the game has said something of [slot] (a document or a menu's row) */
    fun has(slot: Int): Boolean = slots.containsKey(slot)

    /** the pet is gone or dead by a document newer than [since] (uptime ms): its abilities cannot be pressed */
    fun gone(since: Long): Boolean = at > since && (!pet || dead)

    /** a press is waiting for the game's answer (the wheel keeps drawing) */
    @Synchronized fun waiting(now: Long): Boolean = pending.values.any { now - it.second <= PENDING_MS }

    private fun settle(now: Long) {
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val (s, p) = it.next()
            val said = slots[s]?.on
            if (now - p.second > PENDING_MS || said == p.first || said == null) it.remove()
        }
    }

    /** tests: as at the app's start */
    @Synchronized fun reset() { slots = emptyMap(); pet = false; dead = false; fight = false; at = 0L; pending.clear() }
}
