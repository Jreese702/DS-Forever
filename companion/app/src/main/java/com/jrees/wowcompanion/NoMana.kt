package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * NOT ENOUGH MANA, MARKED (Spells' PadMap m71; the user: "can we better track when I am too low on mama for spells?", then "Mark
 * spells"): the spells the player cannot afford now, by spell id, from the add-on's `nomana` document (the game's own noMana where it is
 * plain, else the mana estimate against the spell's cost). The app's spell pictures (the spell wheel, the Spellbook, the class radials,
 * Smart buffs' rows) wear the game's blue out-of-mana look for them; the bars have the game's own per-slot flag. Main thread.
 */
object NoMana {
    @Volatile var ids: Set<Int> = emptySet()
        private set
    /** where the set came from: "game", "model", "mixed", "none" */
    var src: String = "none"
        private set
    private val listeners = ArrayList<() -> Unit>()

    fun onChange(l: () -> Unit) { listeners.add(l) }

    fun has(id: Int?): Boolean = id != null && id > 0 && id in ids

    /** a `nomana` document; true when the set changed */
    fun onDoc(msg: JSONObject): Boolean {
        val a = msg.optJSONArray("ids")
        val next = HashSet<Int>()
        if (a != null) for (k in 0 until a.length()) a.optInt(k, 0).takeIf { it > 0 }?.let { next += it }
        src = msg.optString("src", "none")
        if (next == ids) return false
        ids = next
        for (l in listeners) l()
        return true
    }
}
