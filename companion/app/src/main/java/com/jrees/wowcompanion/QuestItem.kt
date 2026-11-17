package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * A QUEST'S USABLE ITEM (Quests.lua q64; the user, 2026-11-17: "one thing that does make sense though on the home screen quests is
 * showing the Icon for if there is a usable quest Item that needs to be used for the quest"): the item the game's tracker shows beside
 * the quest (QuestObjectiveItemButtonTemplate), from the quests document's `si` - its item id, icon (fileDataID, 0 unknown) and
 * charges (0 none). Home's quest card draws it at its right edge, the Quests room's detail beside the title ([UiKit.questItemButton]).
 * DISPLAY ONLY: the app has no route that uses a quest's item from a touch (the game's own is UseQuestLogSpecialItem, protected; Smart
 * Y's armed item is the pad's). Pure, so the tests read it.
 */
class QuestItem(val id: Int, val icon: Int, val count: Int) {
    companion object {
        /** the quest entry's `si`, or null when it has none */
        fun parse(q: JSONObject?): QuestItem? {
            val si = q?.optJSONObject("si") ?: return null
            val id = si.optInt("id", 0)
            if (id <= 0) return null
            return QuestItem(id, si.optInt("ic", 0).coerceAtLeast(0), si.optInt("c", 0).coerceAtLeast(0))
        }
    }

    /**
     * THE ITEMS' COOLDOWNS, counted here from each LIVE quests document (a replay is an old life's): `si.cd` = { d (length), l (seconds
     * left when sent), h (held: the game's dimmed icon) }. Keyed by item id; an item whose quest says no cd is ready. Counted on the
     * monotonic clock ([clock], ms).
     */
    object Cd {
        private class E(val endMs: Long, val len: Double, val held: Boolean)
        private val at = HashMap<Int, E>()
        @Volatile var clock: () -> Long = { System.nanoTime() / 1_000_000L }

        @Synchronized fun reset() = at.clear()

        /** the quests document's inner object (`q`); [current] = sent this app life */
        fun onQuests(doc: JSONObject, current: Boolean, now: Long = clock()) {
            if (!current) return
            val list = doc.optJSONArray("list") ?: return
            synchronized(this) {
                at.clear()
                for (k in 0 until list.length()) {
                    val si = list.optJSONObject(k)?.optJSONObject("si") ?: continue
                    val id = si.optInt("id", 0)
                    val cd = si.optJSONObject("cd") ?: continue
                    if (id <= 0) continue
                    val d = cd.optDouble("d", 0.0); val l = cd.optDouble("l", 0.0)
                    val held = cd.optBoolean("h", false)
                    if (held) at[id] = E(Long.MAX_VALUE, d, true)
                    else if (d > 0.0 && l > 0.0) at[id] = E(now + (l * 1000.0).toLong(), d, false)
                }
            }
        }

        /** seconds left on item [id], 0.0 = ready / held / unknown */
        @Synchronized fun left(id: Int, now: Long = clock()): Double {
            val e = at[id] ?: return 0.0
            if (e.held) return 0.0
            val l = (e.endMs - now) / 1000.0
            if (l <= 0.0) { at.remove(id); return 0.0 }
            return l
        }
        @Synchronized fun len(id: Int, now: Long = clock()): Double = if (left(id, now) > 0.0) at[id]?.len ?: 0.0 else 0.0
        /** the game's dimmed icon: a length with enable 0 (held until a fight ends) */
        @Synchronized fun held(id: Int): Boolean = at[id]?.held ?: false
    }
}
