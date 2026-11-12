package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * Polestar's live objective, the Map list's light blue row (ps1), read from Map.lua's {"type":"pstep"}.
 * ps3 (the user, 2026-11-11: "The polestar tracked quest went missing from my quests"): a step with no fixed spot (talk / use / train
 * goals; Map.lua sends nl = true and no m / x / y) is still a step - the row stays, its A works, the step's quest is still focused;
 * only what needs a place (the goal sent to Polestar's router, the route, the distance) has none: [hasLoc] false.
 * ps4 (the user, 2026-11-11: "My current polestar objective does not give any direction on where to go"): a quest STARTED BY AN ITEM
 * (Map.lua's it / ih / ic / itn: the accept goal's item, in the bags or not, its icon and name) - [useItem] while it is in the bags:
 * the row reads "Use <item>" and Y uses it (PadMap m132).
 */
class PolestarStep(val title: String?, val words: String?, val kind: String?, val quest: Int, val map: Int, val x: Double, val y: Double,
                   /** ps4 (the user, 2026-11-11: "a polestar objective that is not in my bag and not telling me where to go"): the
                    *  waypoint's world spot (yards, Map.lua's game conversion) and continent, so a map of another zone can mark it;
                    *  NaN / null from an older add-on */
                   val wx: Double = Double.NaN, val wy: Double = Double.NaN, val cont: Int? = null,
                   /** ps4 (ps-noloc-guide): an item-started quest's accept item (Map.lua it / ih / ic / itn) */
                   val itemId: Int = 0, val itemHeld: Boolean = false, val itemIcon: Int = 0, val itemName: String? = null,
                   /** pk1 (the user, 2026-11-12: "we need to make a way to skip a polestar objective"): the add-on can skip Polestar's
                    *  step (Map.lua's sk), and how many skips Previous can still undo (its u) */
                   val skip: Boolean = false, val undo: Int = 0) {
    /** the step has a spot of its own (Polestar's waypoint): map, x, y */
    val hasLoc: Boolean get() = map != 0 && !x.isNaN() && !y.isNaN()
    /** ps4: its world spot is known */
    val hasWorld: Boolean get() = hasLoc && !wx.isNaN() && !wy.isNaN() && cont != null
    /** ps4: the step is an item-started quest's accept and the item is in the bags: using it is the step */
    val useItem: Boolean get() = itemId > 0 && itemHeld
    /** what [useItem]'s line reads (terse: the user's rule) */
    fun useLine(): String = "Use " + (itemName ?: "the item")
    /** pk3: the same step with [n] skips left to undo (a pskip answer's count, before the next step doc) */
    fun withUndo(n: Int): PolestarStep = PolestarStep(title, words, kind, quest, map, x, y, wx, wy, cont, itemId, itemHeld, itemIcon,
        itemName, skip, n.coerceAtLeast(0))
    /** what the row's change check compares (words and the item's state) */
    fun sig(): String = "$title|$words|$itemId|$itemHeld"

    /** ps4: where the guidance goes for a held step ([guide]) */
    sealed class Guide {
        /** Polestar's own waypoint */
        class Point(val map: Int, val x: Double, val y: Double) : Guide()
        /** no point of Polestar's, but the step's quest is in the log: that quest's objective areas, as a focused quest */
        class Quest(val q: Int) : Guide()
        object None : Guide()
    }

    companion object {
        /** the doc's step, or null for {none = true}; a step with no location (nl, or no "m") keeps map 0 and NaN x / y */
        fun parse(d: JSONObject): PolestarStep? {
            if (d.optBoolean("none")) return null
            val title = d.optString("t", "").ifEmpty { null }
            val words = d.optString("g", "").ifEmpty { null }
            val loc = !d.optBoolean("nl") && d.has("m")
            if (!loc && title == null && words == null) return null       // (nothing to show: an older add-on's doc with no step)
            return PolestarStep(title, words, d.optString("k", "").ifEmpty { null }, d.optInt("q", 0),
                if (loc) d.optInt("m", 0) else 0,
                if (loc) d.optDouble("x", Double.NaN) else Double.NaN,
                if (loc) d.optDouble("y", Double.NaN) else Double.NaN,
                if (loc) d.optDouble("wx", Double.NaN) else Double.NaN,
                if (loc) d.optDouble("wy", Double.NaN) else Double.NaN,
                if (loc && d.has("c")) d.optInt("c") else null,
                itemId = d.optInt("it", 0).coerceAtLeast(0), itemHeld = d.optBoolean("ih", false),
                itemIcon = d.optInt("ic", 0).coerceAtLeast(0), itemName = d.optString("itn", "").ifEmpty { null },
                skip = d.optBoolean("sk", false), undo = d.optInt("u", 0).coerceAtLeast(0))
        }

        /**
         * ps4 (the user's approved fallback, 2026-11-11): the held step's guidance - Polestar's point when it has one (it wins); else,
         * its quest in the log ([inLog]), that quest's objective areas exactly as a focused quest's; else nothing (Polestar guides it
         * itself: a talk / an item to use, shown on the row)
         */
        fun guide(st: PolestarStep?, inLog: (Int) -> Boolean): Guide = when {
            st == null -> Guide.None
            st.hasLoc -> Guide.Point(st.map, st.x, st.y)
            st.quest > 0 && inLog(st.quest) -> Guide.Quest(st.quest)
            else -> Guide.None
        }
    }
}
