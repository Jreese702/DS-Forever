package com.jrees.wowcompanion

/**
 * THE MAP ROOM'S SELECTED QUEST AND ITS CONTROLLER KEYS (pure, no Android types: tools/map_test runs it off-device).
 *
 * The user, 2026-09-30 (via tracking): "When selecting quests in the map I keep accidentally moiving it, because my touches go
 * through it- also there is not very good Feedback on when I Select a quest, it should like highlight. we should add some
 * controller inputs here, to be able to scroll down the list of quests, and untrack with X and Focus/Unfocus them with A".
 *
 * ONE selected quest ([sel], a quest id from the tracked list), shown highlighted on its list row AND ringed on its map
 * bubble. A touch selects (and focuses, as before); the controller moves it:
 *   up / down     the previous / next tracked quest (the list scrolls to keep it in view); the first press only SHOWS it
 *   A  (PAD1)     focus it, or unfocus it when it is the focused quest
 *   X  (PAD3)     untrack it (one press = one game action)
 *   Y  (PAD4)     show it on the map
 *   left / right  nothing (the user, 2026-10-07: no D-pad pan, a finger pans and walking recentres; Home takes Right to its
 *                 buffs and Left back from them)
 *   Select / View opens / closes the filter (the user, 2026-10-07: "the filter buton to be there like in other menus and open it
 *                 with start"; moved to Select / View 2026-10-09: Start opens the pause wheel from every room)
 *   LB / RB       zoom out / in (Quests q7 gives the map guest the shoulders)
 *   B  (PAD2)     closes an open filter first, otherwise leaves (Home)
 * The room opens with the selection SHOWN on the FOCUSED quest, else the first (LATER 19 + the user: "it should already be there").
 *
 * THE WHOLE LIST (the user, 2026-10-16; [MapList]): the ids are the list's stops, the portrait ([MapList.ME]) and the untracked tabs
 * ([MapList.isGroup]) among them; [untracked] names the untracked quests. A on the portrait opens the game's player menu, on a tab
 * opens / closes it, on an untracked quest tracks it; X and Y act on tracked quests only.
 */
class MapCursor {
    sealed class Do {
        object Show : Do()
        data class Select(val id: Int) : Do()
        data class Focus(val id: Int) : Do()
        data class Unfocus(val id: Int) : Do()
        data class Untrack(val id: Int) : Do()
        data class ShowOnMap(val id: Int) : Do()
        /** A on the portrait: the game's own player menu */
        object PlayerMenu : Do()
        /** A on an untracked tab: open / close it */
        data class Toggle(val key: Int) : Do()
        /** A on an untracked quest: the game's tracker takes it, and the game's focus (the user, 2026-10-16: "A to track and Focus") */
        data class Track(val id: Int) : Do()
        /** X on an untracked quest: the tracker takes it (X is the inverse of a quest's tracking, tracked or not: the user 2026-10-16) */
        data class Watch(val id: Int) : Do()
        /** Y on an untracked quest: its panel (the quest log's right half, with Share and Abandon) */
        data class Open(val id: Int) : Do()
        data class Zoom(val out: Boolean) : Do()
        object CloseFilter : Do()
        object ToggleFilter : Do()
        object Leave : Do()
        object Nothing : Do()
    }

    /** the selected quest's id, 0 = none */
    var sel = 0
        private set
    /** whether the selection is drawn (a touch or a controller press shows it) */
    var shown = false
        private set
    private var lastIndex = 0

    fun reset() { sel = 0; shown = false; lastIndex = 0 }

    /** a touch picked quest [id] (its row or its bubble): it is selected and shown at once */
    fun touched(id: Int, ids: List<Int>) {
        if (id == 0) return
        sel = id; shown = true
        lastIndex = ids.indexOf(id).coerceAtLeast(0)
    }

    /**
     * The tracked list changed ([ids], in its order). A selected quest that left it (untracked, turned in) hands the
     * selection to the quest now at its place, so X can go down the list one press at a time.
     */
    fun keepValid(ids: List<Int>) {
        if (sel == 0) return
        val i = ids.indexOf(sel)
        if (i >= 0) { lastIndex = i; return }
        if (ids.isEmpty()) { sel = 0; shown = false; return }
        lastIndex = lastIndex.coerceAtMost(ids.size - 1)
        sel = ids[lastIndex]
    }

    /**
     * The room took the controller: the selection is VISIBLE at once (the user: "I hate that in the other menus I have to
     * press buttons a few times to make the cursor appear, it should already be there"), on the focused quest when it is
     * tracked, else on the first. False only when there is no tracked quest at all.
     */
    fun startAt(ids: List<Int>, focusId: Int): Boolean {
        if (ids.isEmpty()) return false
        // the focused quest, else the first quest (not the portrait above them), else the first stop
        val first = ids.indexOfFirst { it > 0 }.coerceAtLeast(0)
        val i = ids.indexOf(focusId).let { if (focusId == 0 || it < 0) first else it }
        sel = ids[i]; shown = true; lastIndex = i
        return true
    }

    /** where the next B leads, for the Home button's B glyph: "back" (closes the filter) or "home" */
    fun bLeads(filterOpen: Boolean): String = if (filterOpen) "back" else "home"

    companion object {
        /**
         * THE GAME'S FOCUS, KEPT RIGHT (the user on test24: "after unfocusing a quest on the minimap, I cant re-focus it" / "I
         * pressed A, it unfocused, and then it keeps the option as unfocus"). Map.lua left superTracked OUT for "none", so the map
         * kept the old focus and A kept offering Unfocus. A map document that carries superTracked says it, 0 = none (Map.lua mp1
         * sends the 0); one without it says nothing.
         */
        fun focusFromMap(hasSt: Boolean, st: Int, current: Int): Int = if (hasSt) maxOf(0, st) else current

        /** the quests document's focus flags: a focus always counts (it may be fresher); "none" only when the map does not say */
        fun focusFromQuests(docFocus: Int, mapSays: Boolean, current: Int): Int = if (docFocus != 0 || !mapSays) docFocus else current
    }

    /** One press [k]; [ids] the tracked quests in the list's order; [focusId] the game's focused quest (0 = none). */
    fun key(k: String, ids: List<Int>, focusId: Int, filterOpen: Boolean, untracked: Set<Int> = emptySet()): Do {
        keepValid(ids)
        if (k == "PAD2") return if (filterOpen) Do.CloseFilter else Do.Leave
        if (k == "PADBACK") return Do.ToggleFilter         // Select / View (Start is the pause wheel, the user 2026-10-09)
        if (filterOpen) return Do.Nothing
        when (k) {
            "PADLSHOULDER" -> return Do.Zoom(out = true)
            "PADRSHOULDER" -> return Do.Zoom(out = false)
        }
        val valid = sel != 0 && ids.contains(sel)
        when (k) {
            "PADDUP", "PADDDOWN" -> {
                if (ids.isEmpty()) return Do.Nothing
                if (!shown || !valid) {
                    shown = true
                    sel = if (valid) sel else if (ids.contains(focusId)) focusId else ids.firstOrNull { it > 0 } ?: ids[0]
                    lastIndex = ids.indexOf(sel)
                    return Do.Show
                }
                val i = ids.indexOf(sel) + (if (k == "PADDDOWN") 1 else -1)
                if (i < 0 || i >= ids.size) return Do.Nothing      // the ends of the list: no wrap
                sel = ids[i]; lastIndex = i
                return Do.Select(sel)
            }
            "PAD1" -> return when {
                !shown || !valid -> Do.Nothing
                sel == MapList.ME -> Do.PlayerMenu
                MapList.isGroup(sel) -> Do.Toggle(sel)
                sel in untracked -> Do.Track(sel)
                sel == focusId -> Do.Unfocus(sel)
                else -> Do.Focus(sel)
            }
            "PAD3" -> return when {
                !shown || !valid -> Do.Nothing
                sel <= 0 -> Do.Nothing
                sel in untracked -> Do.Watch(sel)
                else -> Do.Untrack(sel)
            }
            "PAD4" -> return when {
                !shown || !valid || sel <= 0 -> Do.Nothing
                sel in untracked -> Do.Open(sel)
                else -> Do.ShowOnMap(sel)
            }
        }
        return Do.Nothing
    }
}
