package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * A QUEST'S REWARDS (Quests.lua q63; the user, 2026-11-17: "The quests don't show rewards at all anywhere"): the quests document's
 * `rw` for one quest - money (copper), XP, honor, the choose-one items, the fixed items, the spells. Pure, so the tests read it.
 * The Quests room's detail pane draws it as the game's quest log reward block ([QuestsView]); Home's card does not (the user, 2026-11-17:
 * "I didnt want the rewards on the quests in the home page I think that adds way too much clutter, just in the actual full quest views").
 * The game's item and spell names are shown as the game wrote them (never through Tr).
 */
class QuestRewards(val money: Long, val xp: Int, val honor: Int, val choices: List<Item>, val items: List<Item>, val spells: List<Spell>) {
    /** an item: [name] null = the client had not loaded it yet (the add-on reads it again); [usable] false = the game's red */
    class Item(val name: String?, val icon: Int, val count: Int, val quality: Int, val id: Int, val usable: Boolean)
    class Spell(val name: String, val icon: Int, val id: Int)

    /** one piece the cursor can rest on, in reading order (choices, then the fixed items, then the spells) */
    data class Piece(val kind: Char, val i: Int) {
        val key: String get() = "$kind$i"
    }

    /** the cursor's stops: every item (choice 'c', fixed 'i') and spell ('s') */
    fun pieces(): List<Piece> = choices.indices.map { Piece('c', it) } + items.indices.map { Piece('i', it) } + spells.indices.map { Piece('s', it) }

    fun itemOf(p: Piece): Item? = when (p.kind) { 'c' -> choices.getOrNull(p.i); 'i' -> items.getOrNull(p.i); else -> null }

    companion object {
        /** the quest entry's `rw`, or null when it has none (or nothing in it) */
        fun parse(q: JSONObject?): QuestRewards? {
            val rw = q?.optJSONObject("rw") ?: return null
            fun items(k: String): List<Item> {
                val a = rw.optJSONArray(k) ?: return emptyList()
                return (0 until a.length()).mapNotNull { n ->
                    val o = a.optJSONObject(n) ?: return@mapNotNull null
                    val name = if (o.has("n") && !o.isNull("n")) o.optString("n").ifEmpty { null } else null
                    val id = o.optInt("id", 0)
                    if (name == null && id == 0) null
                    else Item(name, o.optInt("ic", 0), o.optInt("c", 1).coerceAtLeast(1), o.optInt("q", 1), id, o.optBoolean("u", true))
                }
            }
            val sp = rw.optJSONArray("sp")
            val spells = if (sp == null) emptyList() else (0 until sp.length()).mapNotNull { n ->
                val o = sp.optJSONObject(n) ?: return@mapNotNull null
                o.optString("n", "").ifEmpty { null }?.let { Spell(it, o.optInt("ic", 0), o.optInt("id", 0)) }
            }
            val r = QuestRewards(rw.optLong("m", 0).coerceAtLeast(0), rw.optInt("xp", 0).coerceAtLeast(0), rw.optInt("hon", 0).coerceAtLeast(0),
                items("ch"), items("it"), spells)
            return if (r.money == 0L && r.xp == 0 && r.honor == 0 && r.choices.isEmpty() && r.items.isEmpty() && r.spells.isEmpty()) null else r
        }
    }
}

/**
 * THE REWARD ITEMS' TOOLTIPS (Quests.lua q63's {"type":"qrtip"}): the game's own lines per item id, as the add-on sends them once
 * each (replayed documents too: a restarted app reads them back from the log). Process-wide; small.
 */
object QuestRewardTips {
    private const val KEEP = 400
    private val tips = LinkedHashMap<Int, JSONObject>()

    fun onDoc(msg: JSONObject): Boolean {
        val id = msg.optInt("id", 0)
        val tip = msg.optJSONObject("tip") ?: return false
        if (id <= 0) return false
        synchronized(tips) {
            tips.remove(id); tips[id] = tip
            while (tips.size > KEEP) tips.remove(tips.keys.first())
        }
        return true
    }

    fun of(id: Int): JSONObject? = synchronized(tips) { tips[id] }

    /** tests */
    fun clear() = synchronized(tips) { tips.clear() }
}
