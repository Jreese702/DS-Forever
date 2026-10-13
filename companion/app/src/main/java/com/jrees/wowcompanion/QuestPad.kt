package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * WHICH PAD CELLS A QUEST ACTION TAPS. The one place that knows the quests addon's pad rows, for the
 * Quests room and for Home's focus tap.
 *
 * Quests.lua q5 (the user's yes, 2026-09-30: the shared pad was nearly full) has TWO rows:
 *   `qpick`  a quest's column (`slot` in the quests document). It only remembers the quest: no game
 *            action and no reply.
 *   `qact`   the column is the action ([Act.code]), done to the picked quest.
 * So an action on a quest is a PAIR of taps, sent back to back. The addon lets a pick serve one action,
 * for two seconds; an action whose pick was lost is refused ("no quest picked"), never misapplied. Its
 * reply names the quest it acted on: check that id against the one that was meant.
 *
 * An addon older than q5 has one row per action instead. [cells] answers for whichever pad the game
 * published, so the app and the addon can be updated in either order.
 */
object QuestPad {
    /** [reply] is the `op` the addon's questack carries for this action */
    enum class Act(val code: Int, val reply: String, internal val oldRow: String, internal val needsQuest: Boolean) {
        WATCH(0, "watch", "watch", true),
        UNWATCH(1, "unwatch", "unwatch", true),
        /** make the quest the game's focused (super-tracked) one; Home's tap */
        FOCUS(2, "track", "focus", true),
        /** first half of Abandon: the addon names the quest it would drop */
        ARM(3, "arm", "arm", true),
        /** second half of Abandon: drops what ARM named; takes no quest */
        CONFIRM(4, "abandon", "confirm", false),
        /** clear the game's focus, only while this quest is the focused one (Quests.lua q9; the Map room's A on the focused
         *  quest). Quest actions grow DOWN from 39, the controller guests UP from 8. No old row: unavailable before q9. */
        UNFOCUS(39, "untrack", "", true),
        /** share the quest with the group: the game's own Share Quest (Quests.lua q11); refused unless shareable and in a party */
        SHARE(38, "share", "", true),
    }

    /**
     * The cells to tap, in order, as (row, column). Null when this pad offers no way to do [act]:
     * no pad yet, or the quest has no column ([slot] < 0).
     */
    fun cells(pad: JSONObject?, act: Act, slot: Int = 0): List<Pair<String, Int>>? {
        val rows = pad?.optJSONObject("rows") ?: return null
        if (act.needsQuest && slot < 0) return null
        if (rows.has("qpick") && rows.has("qact")) {
            return if (act.needsQuest) listOf("qpick" to slot, "qact" to act.code) else listOf("qact" to act.code)
        }
        if (act.oldRow.isNotEmpty() && rows.has(act.oldRow)) return listOf(act.oldRow to (if (act.needsQuest) slot else 0))
        return null
    }
}
