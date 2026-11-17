package com.jrees.wowcompanion

import android.content.Context
import org.json.JSONObject

/**
 * A QUEST'S USABLE ITEM (Quests.lua q64; the user, 2026-11-17: "one thing that does make sense though on the home screen quests is
 * showing the Icon for if there is a usable quest Item that needs to be used for the quest"): the item the game's tracker shows beside
 * the quest (QuestObjectiveItemButtonTemplate), from the quests document's `si` - its item id, icon (fileDataID, 0 unknown) and
 * charges (0 none). Home's quest card draws it at its right edge, the Quests room's detail beside the title ([UiKit.questItemButton]).
 * A TOUCH ON IT USES IT out of combat (q65, [QuestItemTap]). [quest] / [slot] = its quest's id and qpick column (-1 none). Pure, so
 * the tests read it.
 */
class QuestItem(val id: Int, val icon: Int, val count: Int, val quest: Int = 0, val slot: Int = -1) {
    companion object {
        /** the quest entry's `si`, or null when it has none */
        fun parse(q: JSONObject?): QuestItem? {
            val si = q?.optJSONObject("si") ?: return null
            val id = si.optInt("id", 0)
            if (id <= 0) return null
            val slot = if (q.has("slot") && !q.isNull("slot")) q.optInt("slot", -1) else -1
            return QuestItem(id, si.optInt("ic", 0).coerceAtLeast(0), si.optInt("c", 0).coerceAtLeast(0), q.optInt("id", 0), slot)
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

/**
 * Q65 THE QUEST ITEM BY TAP (the user, 2026-11-17: "make the quest item usable by tap out of combat"): a touch on a quest's item icon
 * (Home's card, the Quests room's detail) taps the quest's qpick column, then pfocus [COL] - Quests.lua's secure quest-item cell, which
 * out of combat uses that quest's item for that one click and is empty again after it. Refused here with a short line, nothing tapped:
 * in a fight ("In combat"), while its cooldown runs or the game holds it ("Not ready"), or with no such cell (an add-on before q65).
 * The add-on refuses again on its side (a fight, no item in the bags) and answers questack "useitem".
 */
object QuestItemTap {
    const val COL = 30

    sealed class Out {
        data class Send(val cells: List<Pair<String, Int>>) : Out()
        data class Refused(val why: String) : Out()
    }

    /** the pair to tap for the quest at qpick column [slot], or null when this pad has no quest-item cell */
    fun cells(pad: JSONObject?, slot: Int): List<Pair<String, Int>>? {
        if (slot < 0) return null
        val rows = pad?.optJSONObject("rows") ?: return null
        if (!rows.has("qpick") || !rows.has("pfocus")) return null
        if ((pad.optJSONObject("sizes")?.optInt("pfocus", 0) ?: 0) <= COL) return null
        return listOf("qpick" to slot, "pfocus" to COL)
    }

    fun decide(item: QuestItem, pad: JSONObject?, fight: Boolean, now: Long = QuestItem.Cd.clock()): Out = when {
        fight -> Out.Refused("In combat")
        QuestItem.Cd.held(item.id) || QuestItem.Cd.left(item.id, now) > 0.0 -> Out.Refused("Not ready")
        else -> cells(pad, item.slot)?.let { Out.Send(it) } ?: Out.Refused("Not ready")
    }

    /** the taps themselves (tests swap it): TapBridge's live gate and quiet pad */
    val REAL: (Context, JSONObject?, List<Pair<String, Int>>, (String) -> Unit) -> Boolean =
        { c, pad, cells, fail -> TapBridge.taps(c, pad, cells, fail) }
    @Volatile var send = REAL

    /** one touch: true = tapped; else [note] says why */
    fun use(ctx: Context, item: QuestItem, pad: JSONObject?, fight: Boolean, note: (String) -> Unit): Boolean =
        when (val o = decide(item, pad, fight)) {
            is Out.Refused -> { note(o.why); false }
            is Out.Send -> send(ctx, pad, o.cells, note).also { if (!it) note("Not ready") }
        }
}
